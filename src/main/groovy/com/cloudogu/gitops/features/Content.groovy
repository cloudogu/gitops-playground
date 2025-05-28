package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.ScmmApiClient
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
        if (config.registry.createImagePullSecrets && config.content.examples) {
            String registryUsername = config.registry.readOnlyUsername ?: config.registry.username
            String registryPassword = config.registry.readOnlyPassword ?: config.registry.password

            List exampleAppNamespaces = ["example-apps-staging", "example-apps-production"]
            exampleAppNamespaces.each {
                String namespace = "${config.application.namePrefix}${it}"
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
        File combinedContentRepoFolder = cloneContentRepos()
        List<RepoCoordinates> repos = parseSrcRepos(combinedContentRepoFolder)
        pushTargetRepos(repos)
    }

    protected File cloneContentRepos() {
        def mergedFolderBasedRepoFolder = File.createTempDir('gitops-playground-folder-based-content-repos')
        mergedFolderBasedRepoFolder.deleteOnExit()
        def engine = new TemplatingEngine()

        log.debug("Aggregating folder structure for all ${config.content.repos.size()} folder based-repos")
        config.content.repos.each { repo ->
            def repoTmpDir = File.createTempDir('gitops-playground-folder-based-content-repo')
            log.debug("Cloning folder-based content repo, ${repo.url}, revision ${repo.ref}, ${repo.path}")

            def cloneCommand = gitClone()
                    .setURI(repo.url)
                    .setDirectory(repoTmpDir)
            
            if (repo.username != null && repo.password != null) {
                cloneCommand.setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider(repo.username, repo.password))
            }
            def git = cloneCommand.call()
            git.checkout().setName(repo.ref).call()

            def srcPath = new File(repoTmpDir, repo.path)
            if (repo.templating) {
                engine.replaceTemplates(srcPath, [
                        config              : config,
                        // Allow for using static classes inside the templates
                        statics             : new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
                ])
            }
            
            if (repo.folderBased) {
                FileUtils.copyDirectory(srcPath, mergedFolderBasedRepoFolder)
            } else {
                // If repo.target has more than two levels for SCM-Manager, only the first two will be used as ns/repo.
                // The remainer will be interpreted as sub-folder
                FileUtils.copyDirectory(srcPath, new File(mergedFolderBasedRepoFolder, repo.target))
            }

            repoTmpDir.delete()
            log.debug("Merge content repo, ${repo.url} into ${mergedFolderBasedRepoFolder}")
        }
        return mergedFolderBasedRepoFolder
    }

    protected static List<RepoCoordinates> parseSrcRepos(File mergedFolderBasedRepoFolder) {
        List<RepoCoordinates> repos = []

        mergedFolderBasedRepoFolder.listFiles().findAll { it.isDirectory() && !it.name.startsWith('.') }
                .each { namespaceDir ->
                    String namespace = namespaceDir.name
                    namespaceDir.listFiles().findAll {
                        it.isDirectory()
                                // Exclude .git for example
                                && !it.name.startsWith('.')
                    }.each { repoDir ->
                        repos << new RepoCoordinates(
                                namespace: namespace,
                                repo: repoDir.name,
                                newContent: repoDir
                        )
                    }
                }

        log.debug("Prepared ${repos.size()} folder-based content repos: ${repos}")
        return repos
    }

    protected void pushTargetRepos(List<RepoCoordinates> srcRepos) {
        srcRepos.each { repoCoordinates ->
            ScmmRepo repo = repoProvider.getRepo("${repoCoordinates.namespace}/${repoCoordinates.repo}")
            // A later iteration will allow setting the description for each folder-based repo
            repo.create('', scmmApiClient)
            repo.cloneRepo()
            repo.copyDirectoryContents(repoCoordinates.newContent.absolutePath)
            repo.commitAndPush("Initialize content repo ${repoCoordinates.namespace}/${repoCoordinates.repo}")
        }
    }

    /**
     * Overwrite for testing purposes
     */
    protected CloneCommand gitClone() {
        Git.cloneRepository()
    }
    
    static class RepoCoordinates {
        String namespace
        String repo
        File newContent

        @Override
        String toString() {
            return "RepoCoordinates{ namespace='$namespace', repo='$repo', newContent=$newContent }"
        }
    }
}