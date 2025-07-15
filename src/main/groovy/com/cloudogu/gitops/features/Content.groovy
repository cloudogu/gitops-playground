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
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

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

            if (ContentRepoType.FOLDER_BASED == repoConfig.type) {
                findRepoDirectories(contentRepoDir)
                        .each { contentRepoNamespaceDir ->
                            findRepoDirectories(contentRepoNamespaceDir)
                                    .each { contentRepoRepoDir ->
                                        String namespace = contentRepoNamespaceDir.name
                                        String repoName = contentRepoRepoDir.name
                                        mergeRepoDirs(contentRepoRepoDir, namespace, repoName, mergedReposFolder,
                                                repoConfig, repoCoordinates)
                                    }
                        }
            } else {
                String namespace = repoConfig.target.split('/')[0]
                String repoName = repoConfig.target.split('/')[1]

                mergeRepoDirs(contentRepoDir, namespace, repoName, mergedReposFolder, repoConfig, repoCoordinates)
            }


            repoTmpDir.deleteDir()
            log.debug("Finished merging content repo, ${repoConfig.url} into ${mergedReposFolder}")
        }
        return repoCoordinates
    }

    /**
     * Merges the files of src into the mergeRepoFolder/namespace/name and adds a new object to repoCoordinates.
     *
     * Note that existing repoCoordinate objects with different overrideMode are overwritten. The last repo to be mentioned within config.content.repos wins!
     */
    private static void mergeRepoDirs(File src, String namespace, String repoName, File mergedRepoFolder,
                                      ContentRepositorySchema repoConfig, List<RepoCoordinate> repoCoordinates) {
        File target = new File(new File(mergedRepoFolder, namespace), repoName)
        log.debug("Merging content repo, namespace ${namespace}, repoName ${repoName} from ${src} to ${target}")
        if (ContentRepoType.MIRROR == repoConfig.type) {
            // In mirror mode, we need not only the files but also commits, tags, branches of content repo.
            // -> Include .git folder
            // BUT: When two MIRROR repos with the same name are specified (e.g. with different refs) .git needs to
            // be overwritten.
            // However, git pack files are typically read-only, leading to IllegalArgumentException: 
            // File parameter 'destFile is not writable: .git/objects/pack/pack-123.pack
            // So: Make writable first
            FileSystemUtils.makeWritable(new File(target, '.git'))
            
            FileUtils.copyDirectory(src, target)
        } else {
            // In all other cases we want to keep the commits of the target repo -> Ignore .git
            FileUtils.copyDirectory(src, target, new FileSystemUtils.IgnoreDotGitFolderFilter())
        }
            

        def repoCoordinate = new RepoCoordinate(
                namespace: namespace,
                repoName: repoName,
                newContent: target,
                repoConfig: repoConfig,
        )
        addRepoCoordinates(repoCoordinates, repoCoordinate)
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
                
                if (!repoCoordinate.repoConfig.ignoreJenkins) {
                    createJenkinsJob(targetRepo, repoCoordinate)
                }
                
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
        targetRepo.commitAndPush("Initialize content repo ${repoCoordinate.namespace}/${repoCoordinate.repoName}")
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
            log.debug("Mirroring ref '${repoCoordinate.repoConfig.ref}' to target repo ${repoCoordinate.fullRepoName} from source ${}")
            targetRepo.pushRef(repoCoordinate.repoConfig.ref, true)
        } else {
            targetRepo.pushAll(true)
        }
    }

    private void createJenkinsJob(ScmmRepo repo, RepoCoordinate repoCoordinate) {
        if (new File(repo.absoluteLocalRepoTmpDir, 'Jenkinsfile').exists()) {
            // namespaces includes all jobs, thats why in this case namespace is uses as job and namespace.
            jenkins.createJenkinsjob(repoCoordinate.namespace, repoCoordinate.namespace)
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
            
            def repoCoordinateToOverwrite = newRepoCoordinate.findSameNotMirror(existingRepoCoordinates)
            if (repoCoordinateToOverwrite) {
                repoCoordinates.remove(repoCoordinateToOverwrite)
                log.debug("Replacing existing repo coordinate ${existingRepoCoordinates} with new one: ${newRepoCoordinate}")
            }
        }
        repoCoordinates << newRepoCoordinate
    }

    static boolean isCommit(File repoPath, String ref) {
        if (!ObjectId.isId(ref)) {
            // This avoids exception on ObjectId.fromString if not ref not a SHA
            return false
        }
        try (Git git = Git.open(repoPath)) {
            ObjectId objectId = ObjectId.fromString(ref)
            if (objectId == null) {
                return false
            }
            // Make sure the ref that looks like a SHA is an actual commit
            try (RevWalk revWalk = new RevWalk(git.repository)) {
                return revWalk.parseAny(objectId) instanceof RevCommit
            }
        } 
    }

    static class RepoCoordinate {
        String namespace
        String repoName
        File newContent
        ContentRepositorySchema repoConfig

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repoName='$repoName', repoConfig.type='${repoConfig.type}', repoConfig.overrideMode='${repoConfig.overrideMode}', newContent=$newContent' }"
        }

        String getFullRepoName() {
            return "${namespace}/${repoName}"
        }

        /**
         * @return all epoCoordinate with the same fullRepoName. There can be one with either COPY/FOLDER_BASED and many MIRRORs.
         */
        List<RepoCoordinate> findSame(List<RepoCoordinate> repoCoordinates) {
            repoCoordinates.findAll() {it.fullRepoName == fullRepoName }
        }

        /**
         * @return RepoCoordinate with the same fullRepoName and repoConfig.type not MIRROR. There can only ever be one!
         */
        RepoCoordinate findSameNotMirror(List<RepoCoordinate> repoCoordinates) {
            repoCoordinates.find() {it.fullRepoName == fullRepoName
                 && ContentRepoType.MIRROR != it.repoConfig.type}
        }
    }

}