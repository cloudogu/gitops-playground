package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Config.OverwriteMode
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.ScmRepoProvider
import com.cloudogu.gitops.git.scmm.api.ScmmApiClient
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
    private ScmRepoProvider repoProvider
    private ScmmApiClient scmmApiClient
    private Jenkins jenkins
    // set by lazy initialisation
    private TemplatingEngine templatingEngine
    // used to clone repos in validation phase
    private List<RepoCoordinate> cachedRepoCoordinates = new ArrayList<>()
    private File mergedReposFolder

    private GitHandler gitHandler

    Content(
            Config config, K8sClient k8sClient, ScmRepoProvider repoProvider, ScmmApiClient scmmApiClient, Jenkins jenkins, GitHandler gitHandler
    ) {
        this.config = config
        this.k8sClient = k8sClient
        this.repoProvider = repoProvider
        this.scmmApiClient = scmmApiClient
        this.jenkins = jenkins
        this.gitHandler = gitHandler
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

    @Override
    void validate() {
        // ensure cache is cleaned
        clearCache()
        // clones repo to check valid configuration and reuse result for further step.
        cachedRepoCoordinates = cloneContentRepos()

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
        if (cachedRepoCoordinates.empty) {
            cachedRepoCoordinates = cloneContentRepos()
        }
        pushTargetRepos(cachedRepoCoordinates)
        // after all, clean folders and list
        clearCache()

    }

    protected List<RepoCoordinate> cloneContentRepos() {
        mergedReposFolder = File.createTempDir('gitops-playground-based-content-repos-')
        List<RepoCoordinate> repoCoordinates = []

        log.debug("Aggregating structure for all ${config.content.repos.size()} repos.")
        config.content.repos.each { repoConfig ->
            createRepoCoordinates(repoConfig, mergedReposFolder, repoCoordinates)
        }
        return repoCoordinates
    }


    private TemplatingEngine getTemplatingEngine() {
        if (templatingEngine == null) {
            templatingEngine = new TemplatingEngine()
        }
        return templatingEngine
    }


    private void createRepoCoordinates(ContentRepositorySchema repoConfig, File mergedReposFolder, List<RepoCoordinate> repoCoordinates) {
        def repoTmpDir = File.createTempDir('gitops-playground-content-repo-')
        log.debug("Cloning content repo, ${repoConfig.url}, revision ${repoConfig.ref}, path ${repoConfig.path}, overwriteMode ${repoConfig.overwriteMode}")

        cloneToLocalFolder(repoConfig, repoTmpDir)

        def contentRepoDir = new File(repoTmpDir, repoConfig.path)
        applyTemplatingIfApplicable(repoConfig, contentRepoDir)


        switch (repoConfig.type) {
            case ContentRepoType.FOLDER_BASED:
                createRepoCoordinatesForTypeFolderBased(repoConfig, repoTmpDir, contentRepoDir, mergedReposFolder, repoCoordinates)
                repoTmpDir.deleteDir()
                break
            case ContentRepoType.COPY:
                createRepoCoordinatesForTypeCopy(repoConfig, contentRepoDir, mergedReposFolder, repoTmpDir, repoCoordinates)
                repoTmpDir.deleteDir()
                break
            case ContentRepoType.MIRROR:
                createRepoCoordinateForTypeMirror(repoConfig, repoTmpDir, repoCoordinates)
                //  intentionally not deleting repoTmpDir, it is contained in RepoCoordinates for MIRROR usage
                break
        }
        log.debug("Finished cloning content repos. repoCoordinates=${repoCoordinates}")
    }

    private static void createRepoCoordinatesForTypeCopy(ContentRepositorySchema repoConfig, File contentRepoDir, File mergedReposFolder, File repoTmpDir, List<RepoCoordinate> repoCoordinates) {
        String namespace = repoConfig.target.split('/')[0]
        String repoName = repoConfig.target.split('/')[1]

        def repoCoordinate = mergeRepoDirs(contentRepoDir, namespace, repoName, mergedReposFolder, repoConfig)
        repoCoordinate.refIsTag = isTag(repoTmpDir, repoConfig.ref)
        addRepoCoordinates(repoCoordinates, repoCoordinate)
    }

    private static void createRepoCoordinatesForTypeFolderBased(ContentRepositorySchema repoConfig, File repoTmpDir, File contentRepoDir, File mergedReposFolder, List<RepoCoordinate> repoCoordinates) {
        boolean refIsTag = isTag(repoTmpDir, repoConfig.ref)
        findRepoDirectories(contentRepoDir)
                .each { contentRepoNamespaceDir ->
                    findRepoDirectories(contentRepoNamespaceDir)
                            .each { contentRepoFolder ->
                                String namespace = contentRepoNamespaceDir.name
                                String repoName = contentRepoFolder.name
                                def repoCoordinate = mergeRepoDirs(contentRepoFolder, namespace, repoName, mergedReposFolder, repoConfig)
                                repoCoordinate.refIsTag = refIsTag
                                addRepoCoordinates(repoCoordinates, repoCoordinate)
                            }
                }
    }

    private static void createRepoCoordinateForTypeMirror(ContentRepositorySchema repoConfig, File repoTmpDir, List<RepoCoordinate> repoCoordinates) {
        // Don't merge but keep these in separate dirs.
        // This avoids messing up .git folders with possible confusing exceptions for the user
        String namespace = repoConfig.target.split('/')[0]
        String repoName = repoConfig.target.split('/')[1]
        def repoCoordinate = new RepoCoordinate(
                namespace: namespace,
                repoName: repoName,
                clonedContentRepo: repoTmpDir,
                repoConfig: repoConfig,
                refIsTag: isTag(repoTmpDir, repoConfig.ref)
        )
        addRepoCoordinates(repoCoordinates, repoCoordinate)
    }

    /**
     * Merges the files of src into the mergeRepoFolder/namespace/name and adds a new object to repoCoordinates.
     *
     * Note that existing repoCoordinate objects with different overwriteMode are overwritten. The last repo to be mentioned within config.content.repos wins!
     */
    private static RepoCoordinate mergeRepoDirs(File src, String namespace, String repoName, File mergedRepoFolder,
                                                ContentRepositorySchema repoConfig) {
        File target = new File(new File(mergedRepoFolder, namespace), repoName)
        log.debug("Merging content repo, namespace ${namespace}, repoName ${repoName} from ${src} to ${target}")
        FileUtils.copyDirectory(src, target, new FileSystemUtils.IgnoreDotGitFolderFilter())

        def repoCoordinate = new RepoCoordinate(
                namespace: namespace,
                repoName: repoName,
                clonedContentRepo: target,
                repoConfig: repoConfig,
        )
        return repoCoordinate
    }

    private static List<File> findRepoDirectories(File srcRepo) {
        srcRepo.listFiles().findAll {
            it.isDirectory() &&
                    // Exclude .git for example
                    !it.name.startsWith('.')
        }
    }

    private void applyTemplatingIfApplicable(ContentRepositorySchema repoConfig, File srcPath) {
        if (repoConfig.templating) {
            def engine = getTemplatingEngine()
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
            def fetch = git.fetch()
            if (repoConfig.username != null && repoConfig.password != null) {
                // fetch also needs CredentialProvider, jgit behaviour.
                fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider(repoConfig.username, repoConfig.password))
            }
            fetch.setRefSpecs("+refs/*:refs/*").call() // Fetch all branches and tags
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


    private void pushTargetRepos(List<RepoCoordinate> repoCoordinates) {
        repoCoordinates.each { repoCoordinate ->

            GitRepo targetRepo = repoProvider.getRepo(repoCoordinate.fullRepoName)
            def isNewRepo = targetRepo.create('', scmmApiClient, false)
            if (isValidForPush(isNewRepo, repoCoordinate)) {
                targetRepo.cloneRepo()

                switch (repoCoordinate.repoConfig.type) {
                    case ContentRepoType.MIRROR:
                        handleRepoMirroring(repoCoordinate, targetRepo)
                        break
                        // COPY and FOLDER_BASED same treatment
                    case ContentRepoType.FOLDER_BASED:
                    case ContentRepoType.COPY:
                        handleRepoCopyingOrFolderBased(repoCoordinate, targetRepo, isNewRepo)
                        break
                }

                createJenkinsJobIfApplicable(repoCoordinate, targetRepo)

                // cleaning tmp folders
                repoCoordinate.clonedContentRepo.deleteDir()
                new File(targetRepo.absoluteLocalRepoTmpDir).deleteDir()
            } // no else needed
        }

    }

    /**
     * Copies repoCoordinate to targetRepo, commits and pushes
     * Same logic for both FOLDER_BASED and COPY repo types.
     */
    private static void handleRepoCopyingOrFolderBased(RepoCoordinate repoCoordinate, GitRepo targetRepo, boolean isNewRepo) {
        if (!isNewRepo) {
            clearTargetRepoIfApplicable(repoCoordinate, targetRepo)
        }
        // Avoid overwriting .git in target to avoid, because we don't need it for copying and
        // git pack files are typically read-only, leading to IllegalArgumentException:
        // File parameter 'destFile is not writable: .git/objects/pack/pack-123.pack
        targetRepo.copyDirectoryContents(repoCoordinate.clonedContentRepo.absolutePath, new FileSystemUtils.IgnoreDotGitFolderFilter())

        String commitMessage = "Initialize content repo ${repoCoordinate.namespace}/${repoCoordinate.repoName}"
        String targetRefShort = repoCoordinate.repoConfig.targetRef.replace('refs/heads/', '').replace('refs/tags/', '')
        if (targetRefShort) {
            String refSpec = setRefSpec(repoCoordinate, targetRefShort)
            targetRepo.commitAndPush(commitMessage, targetRefShort, refSpec)
        } else {
            targetRepo.commitAndPush(commitMessage)
        }

    }

    private static String setRefSpec(RepoCoordinate repoCoordinate, String targetRefShort) {
        String refSpec
        if ((repoCoordinate.refIsTag && !repoCoordinate.repoConfig.targetRef.startsWith('refs/heads'))
                || repoCoordinate.repoConfig.targetRef.startsWith('refs/tags')) {
            refSpec = "refs/tags/${targetRefShort}:refs/tags/${targetRefShort}"
        } else {
            refSpec = "HEAD:refs/heads/${targetRefShort}"
        }
        refSpec
    }

    private static void clearTargetRepoIfApplicable(RepoCoordinate repoCoordinate, GitRepo targetRepo) {
        if (OverwriteMode.INIT != repoCoordinate.repoConfig.overwriteMode) {
            if (OverwriteMode.RESET == repoCoordinate.repoConfig.overwriteMode) {
                log.info("OverwriteMode ${OverwriteMode.RESET} set for repo '${repoCoordinate.fullRepoName}': " +
                        "Deleting existing files in repo and replacing them with new content.")
                targetRepo.clearRepo()
            } else {
                log.debug("OverwriteMode ${OverwriteMode.UPGRADE} set for repo '${repoCoordinate.fullRepoName}': " +
                        "Merging new content into existing repo. ")
            }
        }
    }

    /**
     * Force pushes repoCoordinate.repoConfig.ref or all refs to targetRepo
     */
    private static void handleRepoMirroring(RepoCoordinate repoCoordinate, GitRepo targetRepo) {
        try (def targetGit = Git.open(new File(targetRepo.absoluteLocalRepoTmpDir))) {
            def remoteUrl = targetGit.repository.config.getString('remote', 'origin', 'url')

            // In mirror mode, we mainly need the .git folder to push the whole git history, branches and tags.
            // So copying source to target repo, .git folders are merged.
            // git pack files are typically read-only, leading to  
            // IllegalArgumentException: File parameter 'destFile is not writable: .git/objects/pack/pack-123.pack
            // Workaround: make .git writable.
            // Note: Setting target remote in source repo and pushing from there causes other problems like
            // IOException: Source ref someBranch doesn't resolve to any object.
            FileSystemUtils.makeWritable(new File(targetRepo.absoluteLocalRepoTmpDir, '.git'))

            targetRepo.copyDirectoryContents(repoCoordinate.clonedContentRepo.absolutePath)

            // Restore remote, it could have been overwritten due to a copied .git folder in MIRROR mode
            targetGit.repository.config.setString('remote', 'origin', 'url', remoteUrl)
            targetGit.repository.config.save()
        }

        if (repoCoordinate.repoConfig.ref) {
            validateCommitReferences(repoCoordinate)
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

    private static void validateCommitReferences(RepoCoordinate repoCoordinate) {
        if (isCommit(repoCoordinate.clonedContentRepo, repoCoordinate.repoConfig.ref)) {
            // Mirroring detached commits does not make a lot of sense and is complicated
            // We would have to branch, push, delete remote branch. Considering this an edge case at the moment!
            throw new RuntimeException("Mirroring commit references is not supported for content repos at the moment. content repository '${repoCoordinate.repoConfig.url}', ref: ${repoCoordinate.repoConfig.ref}")
        }
    }

    private void createJenkinsJobIfApplicable(RepoCoordinate repoCoordinate, GitRepo repo) {
        if (repoCoordinate.repoConfig.createJenkinsJob && jenkins.isEnabled()) {
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
    static boolean existFileInSomeBranch(String repo, String filename) {
        String filenameToSearch = filename
        File repoPath = new File(repo + '/.git')

        try (def git = Git.open(repoPath)) {
            List<Ref> branches = git
                    .branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call()

            for (Ref branch : branches) {
                String branchName = branch.getName()

                ObjectId commitId = git.repository.resolve(branchName)
                if (commitId == null) {
                    continue
                }
                try (RevWalk revWalk = new RevWalk(git.repository)) {
                    RevCommit commit = revWalk.parseCommit(commitId)
                    try (TreeWalk treeWalk = new TreeWalk(git.repository)) {

                        treeWalk.addTree(commit.getTree())
                        treeWalk.setFilter(PathFilter.create(filenameToSearch))

                        if (treeWalk.next()) {
                            log.debug("File ${filename} found in branch ${branchName}")

                            return true
                        }
                    }
                }
            }
        }
        log.debug("File ${filename} not found in repository ${repoPath}")
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
    /**
     * Checks whether the repo already exists and overwrite Mode matches.
     */
    static boolean isValidForPush(boolean isNewRepo, RepoCoordinate repoCoordinate) {

        if (!isNewRepo && OverwriteMode.INIT == repoCoordinate.repoConfig.overwriteMode) {
            log.warn("OverwriteMode ${OverwriteMode.INIT} set for repo '${repoCoordinate.fullRepoName}' " +
                    "and repo already exists in target:  Not pushing content!" +
                    "If you want to override, set ${OverwriteMode.UPGRADE} or ${OverwriteMode.RESET} .")
            return false
        }
        return true
    }

    private void clearCache() {
        if (mergedReposFolder) {
            mergedReposFolder.deleteDir()
        }
        cachedRepoCoordinates.clear()
        mergedReposFolder = null
    }

    static class RepoCoordinate {
        String namespace
        String repoName
        File clonedContentRepo
        ContentRepositorySchema repoConfig
        boolean refIsTag

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repoName='$repoName', repoConfig.type='${repoConfig.type}', repoConfig.overwriteMode='${repoConfig.overwriteMode}', clonedContentRepo=$clonedContentRepo', refIsTag='${refIsTag}' }"
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