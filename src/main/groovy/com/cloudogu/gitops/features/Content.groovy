package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
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

            config.content.namespaces.each {String namespace ->
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
        List<RepoCoordinates> repos = cloneContentRepos()
        pushTargetRepos(repos)
    }

    protected List<RepoCoordinates> cloneContentRepos() {
        List<RepoCoordinates> repos = []
        def mergedFolderBasedRepoFolder = File.createTempDir('gitops-playground-folder-based-content-repos-')
        mergedFolderBasedRepoFolder.deleteOnExit()
        def engine = new TemplatingEngine()

        log.debug("Aggregating folder structure for all ${config.content.repos.size()} folder based-repos")
        config.content.repos.each { repo ->

            def repoTmpDir = File.createTempDir('gitops-playground-folder-based-single-content-repo-')
            log.debug("Cloning folder-based content repo, ${repo.url}, revision ${repo.ref}, ${repo.path} OverideMode ${repo.overrideMode}")

            cloneToLocalFolder(repo, repoTmpDir)

            def srcPath = new File(repoTmpDir, repo.path)
            doTemplating(repo, engine, srcPath)

            if (repo.folderBased) {
                srcPath.listFiles().findAll { it.isDirectory() && !it.name.startsWith('.') }
                        .each { namespaceDir ->
                            String namespace = namespaceDir.name
                            namespaceDir.listFiles().findAll {
                                it.isDirectory()
                                        // Exclude .git for example
                                        && !it.name.startsWith('.')
                            }.each { repoDir ->
                                {
                                    def gitIgnoreFilter = FileSystemUtils.createGitIgnoreFilter(mergedFolderBasedRepoFolder.toString())
                                    // Namespace
                                    File directory = new File(mergedFolderBasedRepoFolder, namespace)
                                    // Repo
                                    File repoFolder = new File(directory, repoDir.name)
                                    FileUtils.copyDirectory(repoDir, repoFolder, gitIgnoreFilter)

                                    def repoCoords = new RepoCoordinates(
                                            namespace: namespace,
                                            repo: repoDir.name,
                                            newContent: repoFolder,
                                            overrideMode: repo.overrideMode
                                    )
                                    addRepoCoordinates(repos, repoCoords)
                                }
                            }
                        }

            } else {
                // non folderbased repo
                def gitIgnoreFilter = FileSystemUtils.createGitIgnoreFilter(mergedFolderBasedRepoFolder.toString())
                File contentFolder = new File(mergedFolderBasedRepoFolder, repo.target)
                FileUtils.copyDirectory(srcPath, contentFolder, gitIgnoreFilter)

                String namespace = repo.target.split('/')[0]
                String repoName = repo.target.split('/')[1]
                def repoCoords =  new RepoCoordinates(
                        namespace: namespace,
                        repo: repoName,
                        newContent: contentFolder,
                        overrideMode: repo.overrideMode
                )
                addRepoCoordinates(repos, repoCoords)
            }


            repoTmpDir.deleteDir()
            log.debug("Merge content repo, ${repo.url} into ${mergedFolderBasedRepoFolder}")
        }
        return repos
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

    protected void pushTargetRepos(List<RepoCoordinates> srcRepos) {9
        srcRepos.each { repoCoordinates ->

            ScmmRepo repo = repoProvider.getRepo("${repoCoordinates.namespace}/${repoCoordinates.repo}")
            def isRepoCreated = repo.create('', scmmApiClient)

            // Repo exists and INIT, then nothing happens
            if (!isRepoCreated && Config.OverrideMode.INIT == repoCoordinates.overrideMode) {
                // nothing
            } else {

                repo.cloneRepo()
                if (Config.OverrideMode.RESET == repoCoordinates.overrideMode) {
                    repo.clearRepo()
                }
                repo.copyDirectoryContents(repoCoordinates.newContent.absolutePath)
                repo.commitAndPush("Initialize content repo ${repoCoordinates.namespace}/${repoCoordinates.repo}")
                // cleaning after use
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
     * @param repos
     * @param entry
     */
    void addRepoCoordinates(List<RepoCoordinates> repos, RepoCoordinates entry) {

        repos.removeIf { it.namespace == entry.namespace && it.repo == entry.repo }
        repos << entry
    }

    static class RepoCoordinates {
        String namespace
        String repo
        File newContent
        Config.OverrideMode overrideMode

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repo='$repo', overrideMode='$overrideMode', newContent=$newContent' }"
        }
    }

}