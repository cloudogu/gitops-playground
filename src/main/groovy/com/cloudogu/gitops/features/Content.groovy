package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Config.OverrideMode
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.TemplatingEngine
import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

import static com.cloudogu.gitops.config.Config.ContentRepoType
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema

@Slf4j
@Singleton
@Order(999)
// We want to evaluate content last, to allow for changing all other repos
class Content extends Feature {

    private Config config
    private K8sClient k8sClient
    private ScmmRepoProvider repoProvider
    private ScmmApiClient scmmApiClient
    private Jenkins jenkins

    Content(
            Config config, K8sClient k8sClient, ScmmRepoProvider repoProvider, ScmmApiClient scmmApiClient, Jenkins jenkins
    ) {
        this.config = config
        this.k8sClient = k8sClient
        this.repoProvider = repoProvider
        this.scmmApiClient = scmmApiClient
        this.jenkins = jenkins
    }

    @Override
    boolean isEnabled() {
        return true // for now always on. Once we refactor from Argo CD class we add a param to enable
    }

    @Override
    void enable() {
        createImagePullSecrets()

        createContentRepos()
    }

    void createImagePullSecrets() {
        if (config.registry.createImagePullSecrets) {
            String registryUsername = config.registry.readOnlyUsername ?: config.registry.username
            String registryPassword = config.registry.readOnlyPassword ?: config.registry.password

            config.content.namespaces.each { String namespace ->
                def registrySecretName = 'registry'

                k8sClient.createNamespace(namespace)

                k8sClient.createImagePullSecret(registrySecretName, namespace,
                        config.registry.url /* Only domain matters, path would be ignored */,
                        registryUsername, registryPassword)

                k8sClient.patch('serviceaccount', 'default', namespace,
                        [imagePullSecrets: [[name: registrySecretName]]])

                if (config.registry.twoRegistries) {
                    k8sClient.createImagePullSecret('proxy-registry', namespace,
                            config.registry.proxyUrl, config.registry.proxyUsername,
                            config.registry.proxyPassword)
                }
            }
        }
    }

    void createContentRepos() {
        List<RepoCoordinate> repoCoordinates = cloneContentRepos()
        pushTargetRepos(repoCoordinates)
    }

    protected List<RepoCoordinate> cloneContentRepos() {
        List<RepoCoordinate> repoCoordinates = []
        def mergedReposFolder = File.createTempDir('gitops-playground-folder-based-content-repos-')
        mergedReposFolder.deleteOnExit()
        def engine = new TemplatingEngine()

        log.debug("Aggregating folder structure for all ${config.content.repos.size()} folder based-repos")
        config.content.repos.each { repoConfig ->

            def repoTmpDir = File.createTempDir('gitops-playground-folder-based-single-content-repo-')
            log.debug("Cloning content repo, ${repoConfig.url}, revision ${repoConfig.ref}, path ${repoConfig.path}, overrideMode ${repoConfig.overrideMode}")

            cloneToLocalFolder(repoConfig, repoTmpDir)

            def contentRepoDir = new File(repoTmpDir, repoConfig.path)
            doTemplating(repoConfig, engine, contentRepoDir)


            switch (repoConfig.type) {
                case ContentRepoType.FOLDER_BASED:
                    boolean refIsTag = isTag(repoTmpDir, repoConfig.ref)
                    findRepoDirectories(contentRepoDir)
                            .each { contentRepoNamespaceDir ->
                                findRepoDirectories(contentRepoNamespaceDir)
                                        .each { contentRepoRepoDir ->
                                            String namespace = contentRepoNamespaceDir.name
                                            String repoName = contentRepoRepoDir.name
                                            def repoCoordinate = mergeRepoDirs(contentRepoRepoDir, namespace, repoName, mergedReposFolder, repoConfig, repoCoordinates)
                                            repoCoordinate.refIsTag = refIsTag
                                        }
                            }
                    repoTmpDir.deleteDir()
                    break
                case ContentRepoType.COPY:
                    String namespace = repoConfig.target.split('/')[0]
                    String repoName = repoConfig.target.split('/')[1]

                    def repoCoordinate = mergeRepoDirs(contentRepoDir, namespace, repoName, mergedReposFolder, repoConfig, repoCoordinates)
                    repoCoordinate.refIsTag = isTag(repoTmpDir, repoConfig.ref)
                    repoTmpDir.deleteDir()
                    break
                case ContentRepoType.MIRROR:
                    // Don't merge but keep these in separate dirs.
                    // This avoids messing up .git folders with possible confusing exceptions for the user
                    String namespace = repoConfig.target.split('/')[0]
                    String repoName = repoConfig.target.split('/')[1]
                    def repoCoordinate = new RepoCoordinate(
                            namespace: namespace,
                            repoName: repoName,
                            newContent: repoTmpDir,
                            repoConfig: repoConfig,
                            refIsTag: isTag(repoTmpDir, repoConfig.ref)
                    )
                    repoCoordinates << repoCoordinate
                    break
            }

            log.debug("Finished cloning content repos. repoCoordinates=${repoCoordinates}")
        }
        return repoCoordinates
    }

    /**
     * Merges the files of src into the mergeRepoFolder/namespace/name and adds a new object to repoCoordinates.
     *
     * Note that existing repoCoordinate objects with different overrideMode are overwritten. The last repo to be mentioned within config.content.repos wins!
     */
    private static RepoCoordinate mergeRepoDirs(File src, String namespace, String repoName, File mergedRepoFolder,
                                      ContentRepositorySchema repoConfig, List<RepoCoordinate> repoCoordinates) {
        File target = new File(new File(mergedRepoFolder, namespace), repoName)
        log.debug("Merging content repo, namespace ${namespace}, repoName ${repoName} from ${src} to ${target}")
        FileUtils.copyDirectory(src, target, new FileSystemUtils.IgnoreDotGitFolderFilter())

        def repoCoordinate = new RepoCoordinate(
                namespace: namespace,
                repoName: repoName,
                newContent: target,
                repoConfig: repoConfig,
        )
        addRepoCoordinates(repoCoordinates, repoCoordinate)
        return repoCoordinate
    }

    private static List<File> findRepoDirectories(File srcRepo) {
        srcRepo.listFiles().findAll {
            it.isDirectory() &&
                    // Exclude .git for example
                    !it.name.startsWith('.')
        }
    }

    private void doTemplating(ContentRepositorySchema repoConfig, TemplatingEngine engine, File srcPath) {
        if (repoConfig.templating) {
            engine.replaceTemplates(srcPath, [
                    config : config,
                    // Allow for using static classes inside the templates
                    statics: new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
            ])
        }
    }


    private void cloneToLocalFolder(ContentRepositorySchema repoConfig, File repoTmpDir) {

        def cloneCommand = gitClone()
                .setURI(repoConfig.url)
                .setDirectory(repoTmpDir)
                .setNoCheckout(false) // Checkout default branch

        if (repoConfig.username != null && repoConfig.password != null) {
            cloneCommand.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(repoConfig.username, repoConfig.password))
        }


        def git = cloneCommand.call()

        if (ContentRepoType.MIRROR == repoConfig.type) {
            git.fetch().setRefSpecs("+refs/*:refs/*").call() // Fetch all branches and tags
        }

        if (repoConfig.ref) {
            def actualRef = findRef(repoConfig, git.repository)
            git.checkout().setName(actualRef).call()
        }
    }

    private static String findRef(ContentRepositorySchema repoConfig, Repository gitRepo) {
        // Check if ref exists first to avoid InvalidRefNameException
        // Note that this works for commits and shortname tags but not shortname branches 🙄
        if (gitRepo.resolve(repoConfig.ref)) {
            return repoConfig.ref
        }

        // Check branches or tags
        def remoteCommand = Git.lsRemoteRepository()
                .setRemote(repoConfig.url)
                .setHeads(true)
                .setTags(true)

        if (repoConfig.username != null && repoConfig.password != null) {
            remoteCommand.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(repoConfig.username, repoConfig.password))
        }
        Collection<Ref> refs = remoteCommand.call()
        String potentialRef = refs.find { it.name.endsWith(repoConfig.ref) }?.name

        if (!potentialRef) {
            // Jgit silently ignores some missing refs and just continues with default branch.
            // This might lead to unexpected surprises for our users, so better fail explicitly
            throw new RuntimeException("Reference '${repoConfig.ref}' not found in content repository '${repoConfig.url}'")
        }

        // Jgit only checks out remote branches when they start in origin/ 🙄 
        return potentialRef.replace('refs/heads/', 'origin/')
    }


    protected void pushTargetRepos(List<RepoCoordinate> repoCoordinates) {
        repoCoordinates.each { repoCoordinate ->

            ScmmRepo targetRepo = repoProvider.getRepo(repoCoordinate.fullRepoName)
            def isRepoCreated = targetRepo.create('', scmmApiClient)

            if (!isRepoCreated && OverrideMode.INIT == repoCoordinate.repoConfig.overrideMode) {
                log.warn("OverrideMode ${OverrideMode.INIT} set for repo '${repoCoordinate.fullRepoName}' " +
                        "and repo already exists in target:  Not pushing content!" +
                        "If you want to override, set ${OverrideMode.UPGRADE} or ${OverrideMode.RESET} .")
            } else {
                
                targetRepo.cloneRepo()
                
                if (ContentRepoType.MIRROR == repoCoordinate.repoConfig.type) {
                    handleRepoMirroring(repoCoordinate, targetRepo)
                } else {
                    handleRepoCopying(repoCoordinate, targetRepo)
                }

                createJenkinsJob(repoCoordinate, targetRepo)

                new File(targetRepo.absoluteLocalRepoTmpDir).deleteDir()
            }
        }

    }

    /**
     * Copies repoCoordinate to targetRepo, commits and pushes
     * Same logic for both FOLDER_BASED and COPY repo types.
     */
    private static void handleRepoCopying(RepoCoordinate repoCoordinate, ScmmRepo targetRepo) {
        if (OverrideMode.INIT != repoCoordinate.repoConfig.overrideMode) {
            if (OverrideMode.RESET == repoCoordinate.repoConfig.overrideMode) {
                log.info("OverrideMode ${OverrideMode.RESET} set for repo '${repoCoordinate.fullRepoName}': " +
                        "Deleting existing files in repo and replacing them with new content.")
                targetRepo.clearRepo()
            } else {
                log.info("OverrideMode ${OverrideMode.UPGRADE} set for repo '${repoCoordinate.fullRepoName}': " +
                        "Merging new content into existing repo. ")
            }
        }
        // Avoid overwriting .git in target to avoid, because we don't need it for copying and
        // git pack files are typically read-only, leading to IllegalArgumentException:
        // File parameter 'destFile is not writable: .git/objects/pack/pack-123.pack
        targetRepo.copyDirectoryContents(repoCoordinate.newContent.absolutePath, new FileSystemUtils.IgnoreDotGitFolderFilter())

        String commitMessage = "Initialize content repo ${repoCoordinate.namespace}/${repoCoordinate.repoName}"
        String targetRefShort = repoCoordinate.repoConfig.targetRef.replace('refs/heads/', '').replace('refs/tags/', '')
        if (targetRefShort) {
            String refSpec
            if ((repoCoordinate.refIsTag && !repoCoordinate.repoConfig.targetRef.startsWith('refs/heads')) 
                    || repoCoordinate.repoConfig.targetRef.startsWith('refs/tags')) {
                refSpec = "refs/tags/${targetRefShort}:refs/tags/${targetRefShort}"
            } else {
                refSpec = "HEAD:refs/heads/${targetRefShort}" 
            }
            
            targetRepo.commitAndPush(commitMessage, targetRefShort, refSpec)
        } else {
            targetRepo.commitAndPush(commitMessage)
        }
            
    }

    /**
     * Force pushes repoCoordinate.repoConfig.ref or all refs to targetRepo
     */
    private static void handleRepoMirroring(RepoCoordinate repoCoordinate, ScmmRepo targetRepo) {
        try (def targetGit = Git.open(new File(targetRepo.absoluteLocalRepoTmpDir))) {
            def remoteUrl = targetGit.repository.config.getString('remote', 'origin', 'url')

            targetRepo.copyDirectoryContents(repoCoordinate.newContent.absolutePath)

            // Restore remote, it could have been overwritten due to a copied .git folder in MIRROR mode
            targetGit.repository.config.setString('remote', 'origin', 'url', remoteUrl)
            targetGit.repository.config.save()
        }

        if (repoCoordinate.repoConfig.ref) {
            if (isCommit(repoCoordinate.newContent, repoCoordinate.repoConfig.ref)) {
                // Mirroring detached commits does not make a lot of sense and is complicated
                // We would have to branch, push, delete remote branch. Considering this an edge case at the moment!
                throw new RuntimeException("Mirroring commit references is not supported for content repos at the moment. content repository '${repoCoordinate.repoConfig.url}', ref: ${repoCoordinate.repoConfig.ref}")
            }
            if (repoCoordinate.repoConfig.targetRef) {
                log.debug("Mirroring repo '${repoCoordinate.repoConfig.url}' ref '${repoCoordinate.repoConfig.ref}' to target repo ${repoCoordinate.fullRepoName}, targetRef: '${repoCoordinate.repoConfig.targetRef}'")
                targetRepo.pushRef(repoCoordinate.repoConfig.ref, repoCoordinate.repoConfig.targetRef, true)
            } else {
                log.debug("Mirroring repo '${repoCoordinate.repoConfig.url}' ref '${repoCoordinate.repoConfig.ref}' to target repo ${repoCoordinate.fullRepoName}")
                targetRepo.pushRef(repoCoordinate.repoConfig.ref, true)
            } 
        } else {
            log.debug("Mirroring whole repo '${repoCoordinate.repoConfig.url}' to target repo ${repoCoordinate.fullRepoName}")
            targetRepo.pushAll(true)
        }
    }

    private void createJenkinsJob(RepoCoordinate repoCoordinate, ScmmRepo repo) {
        // easy condition
        if (!repoCoordinate.repoConfig.ignoreJenkins && jenkins.isEnabled()) {
            // this conditions iterates over all branches
            if (existFileInSomeBranch(repo.absoluteLocalRepoTmpDir, 'Jenkinsfile')) {
                jenkins.createJenkinsjob(repoCoordinate.namespace, repoCoordinate.namespace)
            }
        }
    }

    /**
     * Overwrite for testing purposes
     */
    protected CloneCommand gitClone() {
        Git.cloneRepository()
    }

    /**
     * Add new repoCoordinates to repos and ensure, newest one override last one.
     * Except for MIRROR, which will have to run separately from COPY/FOLDER_BASED in order to allow overriding by COPY/FOLDER_BASED repoCoordinates for the same repo.
     */
    static void addRepoCoordinates(List<RepoCoordinate> repoCoordinates, RepoCoordinate newRepoCoordinate) {
        def existingRepoCoordinates = newRepoCoordinate.findSame(repoCoordinates)

        if (!existingRepoCoordinates.isEmpty()) {
            log.debug("Found existing repo coordinates for ${newRepoCoordinate}: ${existingRepoCoordinates}")

            // Don't replace MIRROR coordinates, they are separate git operations
            def repoCoordinateToOverwrite = newRepoCoordinate.findSameNotMirror(existingRepoCoordinates)
            if (repoCoordinateToOverwrite) {
                repoCoordinates.remove(repoCoordinateToOverwrite)
                log.debug("Replacing existing repo coordinate ${existingRepoCoordinates} with new one: ${newRepoCoordinate}")
            }
        }
        repoCoordinates << newRepoCoordinate
    }

    static boolean isCommit(File repoPath, String ref) {
        if (!ref) {
            return false
        }

        try (Git git = Git.open(repoPath)) {
            // Get all branch and tag names
            def allRefs = []

            // Add all branch names (without refs/heads/ prefix)
            git.branchList().call().each { branch ->
                allRefs.add(branch.name.replaceFirst('refs/heads/', ''))
            }

            // Add all tag names (without refs/tags/ prefix)
            git.tagList().call().each { tag ->
                allRefs.add(tag.name.replaceFirst('refs/tags/', ''))
            }

            // If the ref matches any branch or tag name, it's not a commit hash
            if (allRefs.contains(ref)) {
                return false
            }

            // If it's not a branch or tag, try to resolve it as a commit
            def objectId = git.repository.resolve(ref)
            return objectId != null

        } 
    }
    /**
     * checks, if file exists in repo in some branch.
     * @param pathToRepo
     * @param filename
     */
    boolean existFileInSomeBranch(String repo, String filename) {
        String filenameToSearch = filename
        File repoPath = new File(repo + '/.git')
        def repository = new FileRepositoryBuilder()
                .setGitDir(repoPath)
                .build()

        try (def git = Git.open(repoPath)) {
            List<Ref> branches = git
                    .branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call()

            for (Ref branch : branches) {
                String branchName = branch.getName()

                ObjectId commitId = repository.resolve(branchName)
                if (commitId == null) {
                    continue
                }
                try (RevWalk revWalk = new RevWalk(repository)) {
                    RevCommit commit = revWalk.parseCommit(commitId)
                    TreeWalk treeWalk = new TreeWalk(repository)
                    treeWalk.addTree(commit.getTree())
                    treeWalk.setRecursive(true)
                    treeWalk.setFilter(PathFilter.create(filenameToSearch))

                    if (treeWalk.next()) {
                        log.info("File ${filename} found in branch ${branchName}")
                        treeWalk.close()
                        revWalk.close()
                        return true
                    }

                    treeWalk.close()
                    revWalk.close()

                }
            }
        }
        log.info("File ${filename} not found in repository ${repoPath}")
        return false
    }

    static boolean isTag(File repo, String ref) {
        if (!ref) {
            return false
        }
        try (def git = Git.open(repo)) {
            git.tagList().call().any { it.name.endsWith("/" + ref) || it.name == ref } 
        }
    }

    static class RepoCoordinate {
        String namespace
        String repoName
        File newContent
        ContentRepositorySchema repoConfig
        boolean refIsTag

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repoName='$repoName', repoConfig.type='${repoConfig.type}', repoConfig.overrideMode='${repoConfig.overrideMode}', newContent=$newContent', refIsTag='${refIsTag}' }"
        }

        String getFullRepoName() {
            return "${namespace}/${repoName}"
        }

        /**
         * @return all epoCoordinate with the same fullRepoName. There can be one with either COPY/FOLDER_BASED and many MIRRORs.
         */
        List<RepoCoordinate> findSame(List<RepoCoordinate> repoCoordinates) {
            repoCoordinates.findAll() { it.fullRepoName == fullRepoName }
        }

        /**
         * @return RepoCoordinate with the same fullRepoName and repoConfig.type not MIRROR. There can only ever be one!
         */
        RepoCoordinate findSameNotMirror(List<RepoCoordinate> repoCoordinates) {
            repoCoordinates.find() {
                it.fullRepoName == fullRepoName
                        && ContentRepoType.MIRROR != it.repoConfig.type
            }
        }
    }

}