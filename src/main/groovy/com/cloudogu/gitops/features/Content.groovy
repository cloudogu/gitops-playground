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
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

@Slf4j
@Singleton
@Order(80)
class Content extends Feature {

    private Config config
    private K8sClient k8sClient
    private ScmmRepoProvider repoProvider
    private ScmmApiClient scmmApiClient

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

            if (repo.folderBased) {
                findRepoDirectories(contentRepoDir)
                        .each { contentRepoNamespaceDir ->
                            findRepoDirectories(contentRepoNamespaceDir)
                                    .each { contentRepoRepoDir ->
                                        String namespace = contentRepoNamespaceDir.name
                                        String repoName = contentRepoRepoDir.name

                                        mergeRepoDirs(contentRepoRepoDir, namespace, repoName, mergedReposFolder, repo.overrideMode, repoCoordinates)
                                    }
                        }
            } else {
                String namespace = repo.target.split('/')[0]
                String repoName = repo.target.split('/')[1]

                mergeRepoDirs(contentRepoDir, namespace, repoName, mergedReposFolder, repo.overrideMode, repoCoordinates)
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
                                      OverrideMode overrideMode, List<RepoCoordinate> repoCoordinates) {
        File target = new File(new File(mergedRepoFolder, namespace), repoName)
        log.debug("Merging content repo, namespace ${namespace}, repoName ${repoName} from ${src} to ${target}")
        FileUtils.copyDirectory(src, target, new FileSystemUtils.IgnoreDotGitFolderFilter())

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
                .setCloneAllBranches(true)

        if (repo.username != null && repo.password != null) {
            cloneCommand.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(repo.username, repo.password))
        }
        def git = cloneCommand.call()
        try {
            // switch to used branch.
            git.checkout().setName(repo.ref).call()

        } catch (GitAPIException e) {
            // This is a fallback because of branches hosted at github.
            log.debug("checkout branch ${repo.ref} not working, maybe because of github. Now again with origin/${repo.ref} to checkout remote branch.")
            var nameWithOrigin = 'origin/'+ repo.ref
            git.checkout().setName(nameWithOrigin).call()

        }
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
                
                new File(repo.absoluteLocalRepoTmpDir).deleteDir()
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

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repo='$repo', overrideMode='$overrideMode', newContent=$newContent' }"
        }

        String getFullRepoName() {
            return "${namespace}/${repo}"
        }
    }

}