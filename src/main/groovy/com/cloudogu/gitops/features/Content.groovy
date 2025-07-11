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
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

import java.nio.file.FileSystems
import java.nio.file.Paths

@Slf4j
@Singleton
@Order(999)
// We want to evaluate content last, to allow for changing all other repos
class Content extends Feature {

    private Config config
    private K8sClient k8sClient
    private ScmmRepoProvider repoProvider
    private ScmmApiClient scmmApiClient
    @Inject
    private Jenkins jenkins

    Content(
            Config config, K8sClient k8sClient, ScmmRepoProvider repoProvider, ScmmApiClient scmmApiClient
    ) {
        this.config = config
        this.k8sClient = k8sClient
        this.repoProvider = repoProvider
        this.scmmApiClient = scmmApiClient
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
        config.content.repos.each { repo ->

            def repoTmpDir = File.createTempDir('gitops-playground-folder-based-single-content-repo-')
            log.debug("Cloning content repo, ${repo.url}, revision ${repo.ref}, path ${repo.path}, overrideMode ${repo.overrideMode}")

            cloneToLocalFolder(repo, repoTmpDir)

            def contentRepoDir = new File(repoTmpDir, repo.path)
            doTemplating(repo, engine, contentRepoDir)
            List<String> exludes = repo.excludes
            if (repo.folderBased) {
                findRepoDirectories(contentRepoDir)
                        .each { contentRepoNamespaceDir ->
                            findRepoDirectories(contentRepoNamespaceDir)
                                    .each { contentRepoRepoDir ->
                                        String namespace = contentRepoNamespaceDir.name
                                        String repoName = contentRepoRepoDir.name

                                        mergeRepoDirs(contentRepoRepoDir, namespace, repoName, mergedReposFolder, repo.overrideMode, repoCoordinates, exludes)
                                    }
                        }
            } else {
                String namespace = repo.target.split('/')[0]
                String repoName = repo.target.split('/')[1]

                mergeRepoDirs(contentRepoDir, namespace, repoName, mergedReposFolder, repo.overrideMode, repoCoordinates, exludes)
            }


            repoTmpDir.deleteDir()
            log.debug("Finished merging content repo, ${repo.url} into ${mergedReposFolder}")
        }
        return repoCoordinates
    }

    /**
     * Merges the files of src into the mergeRepoFolder/namespace/name and adds a new object to repoCoordinates.
     *
     * Note that existing repoCoordinate objects with different overrideMode are overwritten. The last repo to be mentioned within config.content.repos wins!
     */
    private static void mergeRepoDirs(File src, String namespace, String repoName, File mergedRepoFolder,
                                      OverrideMode overrideMode, List<RepoCoordinate> repoCoordinates, List<String> excludes = new ArrayList<>()) {
        File target = new File(new File(mergedRepoFolder, namespace), repoName)
        log.debug("Merging content repo, namespace ${namespace}, repoName ${repoName} from ${src} to ${target}")

        // .git will be ignored in every case
        excludes.add("**/.git")

        def matchers = excludes.collect { pattern ->
            FileSystems.default.getPathMatcher("glob:" + pattern.replace("/", File.separator))
        }

        def shouldExclude = { File file ->
            def relativePath = Paths.get("").toAbsolutePath().relativize(file.toPath().toAbsolutePath())
            matchers.any { it.matches(relativePath) }
        }

        FileFilter filter = { File file -> !shouldExclude(file) } as FileFilter

        FileUtils.copyDirectory(src, target, filter)

        def repoCoordinate = new RepoCoordinate(
                namespace: namespace,
                repo: repoName,
                newContent: target,
                overrideMode: overrideMode
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

    private void doTemplating(Config.ContentSchema.ContentRepositorySchema repo, TemplatingEngine engine, File srcPath) {
        if (repo.templating) {
            engine.replaceTemplates(srcPath, [
                    config : config,
                    // Allow for using static classes inside the templates
                    statics: new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
            ])
        }
    }


    private void cloneToLocalFolder(Config.ContentSchema.ContentRepositorySchema repo, File repoTmpDir) {

        def cloneCommand = gitClone()
                .setURI(repo.url)
                .setDirectory(repoTmpDir)
                .setNoCheckout(false) // Checkout default branch

        if (repo.username != null && repo.password != null) {
            cloneCommand.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(repo.username, repo.password))
        }
        def git = cloneCommand.call()

        if (repo.ref) {
            def actualRef = findRef(repo, git.repository)
            git.checkout().setName(actualRef).call()
        }
    }

    private String findRef(Config.ContentSchema.ContentRepositorySchema repoConfig, Repository gitRepo) {
        // Check if it is a commit hash first to avoid InvalidRefNameException
        if (gitRepo.resolve(repoConfig.ref)) {
            return repoConfig.ref
        }

        // Check tags or branches
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
            throw new RuntimeException("Reference '${repoConfig.ref}' not found in repository '${repoConfig.url}'")
        }

        // Jgit only checks out remote branches when they start in origin/ 🙄 
        return potentialRef.replace('refs/heads/', 'origin/')
    }


    protected void pushTargetRepos(List<RepoCoordinate> repoCoordinates) {
        repoCoordinates.each { repoCoordinate ->

            ScmmRepo repo = repoProvider.getRepo(repoCoordinate.fullRepoName)
            def isRepoCreated = repo.create('', scmmApiClient)

            if (!isRepoCreated && OverrideMode.INIT == repoCoordinate.overrideMode) {
                log.warn("OverrideMode ${OverrideMode.INIT} set for repo '${repoCoordinate.fullRepoName}' " +
                        "and repo already exists in target:  Not pushing content!" +
                        "If you want to override, set ${OverrideMode.UPGRADE} or ${OverrideMode.RESET} .")
            } else {

                repo.cloneRepo()

                if (OverrideMode.INIT != repoCoordinate.overrideMode) {
                    if (OverrideMode.RESET == repoCoordinate.overrideMode) {
                        log.info("OverrideMode ${OverrideMode.RESET} set for repo '${repoCoordinate.fullRepoName}': " +
                                "Deleting existing files in repo and replacing them with new content.")
                        repo.clearRepo()
                    } else {
                        log.info("OverrideMode ${OverrideMode.UPGRADE} set for repo '${repoCoordinate.fullRepoName}': " +
                                "Merging new content into existing repo. ")
                    }
                }

                repo.copyDirectoryContents(repoCoordinate.newContent.absolutePath)
                repo.commitAndPush("Initialize content repo ${repoCoordinate.namespace}/${repoCoordinate.repo}")

                createJenkinsJob(repo, repoCoordinate)

                new File(repo.absoluteLocalRepoTmpDir).deleteDir()
            }
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
     * add new repoCoordinates to repos and ensure, newest one override last one
     */
    static void addRepoCoordinates(List<RepoCoordinate> repoCoordinates, RepoCoordinate entry) {
        if (repoCoordinates.removeIf { it.namespace == entry.namespace && it.repo == entry.repo }) {
            log.debug("Repo coordinate ${entry} replaced existing")
        }
        repoCoordinates << entry
    }

    static class RepoCoordinate {
        String namespace
        String repo
        File newContent
        OverrideMode overrideMode
        List<String> exlcudes  = new ArrayList<>()

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repo='$repo', overrideMode='$overrideMode', newContent=$newContent', exludes=${exlcudes.toListString()} }"
        }

        String getFullRepoName() {
            return "${namespace}/${repo}"
        }
    }

}