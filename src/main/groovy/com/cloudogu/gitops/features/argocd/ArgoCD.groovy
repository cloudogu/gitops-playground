package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.*
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
@Singleton
@Order(100)
class ArgoCD extends Feature {
    static final String HELM_VALUES_PATH = 'argocd/values.yaml'
    static final String CHART_YAML_PATH = 'argocd/Chart.yaml'
    static final String NGINX_HELM_JENKINS_VALUES_PATH = 'k8s/values-shared.yaml'
    static final String NGINX_HELM_DEPENDENCY_VALUES_PATH = 'apps/nginx-helm-umbrella/values.yaml'
    static final String NGINX_VALIDATION_VALUES_PATH = 'k8s/values-shared.yaml'
    static final String BROKEN_APPLICATION_RESOURCES_PATH = 'broken-application.yaml'
    static final String SCMM_URL_INTERNAL = "http://scmm-scm-manager.default.svc.cluster.local/scm"
    private Map config
    private List<RepoInitializationAction> gitRepos = []

    private String password
    
    protected RepoInitializationAction argocdRepoInitializationAction
    protected RepoInitializationAction clusterResourcesInitializationAction
    protected RepoInitializationAction exampleAppsInitializationAction
    protected RepoInitializationAction nginxHelmJenkinsInitializationAction
    protected RepoInitializationAction nginxValidationInitializationAction
    protected RepoInitializationAction brokenApplicationInitializationAction
    protected File remotePetClinicRepoTmpDir
    protected List<RepoInitializationAction> petClinicInitializationActions = []

    protected K8sClient k8sClient
    protected HelmClient helmClient

    protected FileSystemUtils fileSystemUtils
    private ScmmRepoProvider repoProvider

    ArgoCD(
            Configuration config,
            K8sClient k8sClient,
            HelmClient helmClient,
            FileSystemUtils fileSystemUtils,
            ScmmRepoProvider repoProvider
    ) {
        this.repoProvider = repoProvider
        this.config = config.getConfig()
        this.k8sClient = k8sClient
        this.helmClient = helmClient
        this.fileSystemUtils = fileSystemUtils
        
        this.password = this.config.application["password"]

        argocdRepoInitializationAction = createRepoInitializationAction('argocd/argocd', 'argocd/argocd')

        clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources')
        gitRepos += clusterResourcesInitializationAction

        exampleAppsInitializationAction = createRepoInitializationAction('argocd/example-apps', 'argocd/example-apps')
        gitRepos += exampleAppsInitializationAction
        
        nginxHelmJenkinsInitializationAction = createRepoInitializationAction('applications/argocd/nginx/helm-jenkins', 'argocd/nginx-helm-jenkins')
        gitRepos += nginxHelmJenkinsInitializationAction

        nginxValidationInitializationAction = createRepoInitializationAction('exercises/nginx-validation', 'exercises/nginx-validation')
        gitRepos += nginxValidationInitializationAction

        brokenApplicationInitializationAction = createRepoInitializationAction('exercises/broken-application', 'exercises/broken-application')
        gitRepos += brokenApplicationInitializationAction

        remotePetClinicRepoTmpDir = File.createTempDir('gitops-playground-petclinic')


        def petclinicInitAction = createRepoInitializationAction('applications/argocd/petclinic/plain-k8s', 'argocd/petclinic-plain')
        petClinicInitializationActions += petclinicInitAction
        gitRepos += petclinicInitAction

        petclinicInitAction = createRepoInitializationAction('applications/argocd/petclinic/helm', 'argocd/petclinic-helm')
        petClinicInitializationActions += petclinicInitAction
        gitRepos += petclinicInitAction

        petclinicInitAction = createRepoInitializationAction('exercises/petclinic-helm', 'exercises/petclinic-helm')
        petClinicInitializationActions += petclinicInitAction
        gitRepos += petclinicInitAction
    }

    @Override
    boolean isEnabled() {
        config.features['argocd']['active']
    }

    @Override
    void enable() {
        log.info("Cloning Repositories")
        cloneRemotePetclinicRepo()
        
        gitRepos.forEach( repoInitializationAction -> {
            repoInitializationAction.initLocalRepo()
        })

        prepareGitOpsRepos()

        prepareApplicationNginxHelmJenkins()

        preparePetClinicRepos()

        prepareExerciseNginxValidationRepo()
        prepareExerciseBrokenApplicationRepo()

        gitRepos.forEach( repoInitializationAction -> {
            repoInitializationAction.repo.commitAndPush("Initial Commit")
        })

        log.info("Installing Argo CD")
        installArgoCd()
    }

    private void cloneRemotePetclinicRepo() {
        log.debug("Cloning petclinic base repo, revision ${config.repositories['springPetclinic']['ref']}," +
                " from ${config.repositories['springPetclinic']['url']}")
        Git git = gitClone()
                .setURI(config.repositories['springPetclinic']['url'].toString())
                .setDirectory(remotePetClinicRepoTmpDir)
                .call()
        git.checkout().setName(config.repositories['springPetclinic']['ref'].toString()).call()
        log.debug('Finished cloning petclinic base repo')
    }

    /**
     * Overwrite for testing purposes
     */
    protected CloneCommand gitClone() {
        Git.cloneRepository()
    }

    private void prepareGitOpsRepos() {

        if (!config.features['secrets']['active']) {
            log.debug("Deleting unnecessary secrets folder from cluster resources: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteDir clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/misc/secrets'
        }

        if (!config.features['monitoring']['active']) {
            log.debug("Deleting unnecessary monitoring folder from cluster resources: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteDir clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/misc/monitoring'
        }

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in gitops repos to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(new File(clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), SCMM_URL_INTERNAL, externalScmmUrl)
            replaceFileContentInYamls(new File(exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), SCMM_URL_INTERNAL, externalScmmUrl)
        }

        fileSystemUtils.copyDirectory("${fileSystemUtils.rootDir}/applications/argocd/nginx/helm-umbrella",
                Path.of(exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'apps/nginx-helm-umbrella/').toString())
        if (!config.application["remote"]) {
            //  Set NodePort service, to avoid "Pending" services and "Processing" state in argo on local cluster
            log.debug("Setting service.type to NodePort since it is not running in a remote cluster for nginx-helm-umbrella")
            def nginxHelmValuesTmpFile = Path.of exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), NGINX_HELM_DEPENDENCY_VALUES_PATH
            Map nginxHelmValuesYaml = fileSystemUtils.readYaml(nginxHelmValuesTmpFile)
            MapUtils.deepMerge([
                    nginx: [
                            service: [
                                    type: 'NodePort'
                            ]
                    ]
            ], nginxHelmValuesYaml)
            log.trace("nginx-helm-umbrella values yaml: ${nginxHelmValuesYaml}")
            fileSystemUtils.writeYaml(nginxHelmValuesYaml, nginxHelmValuesTmpFile.toFile())
        }
    }

    private void prepareApplicationNginxHelmJenkins() {

        def nginxHelmJenkinsValuesTmpFile = Path.of nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), NGINX_HELM_JENKINS_VALUES_PATH
        Map nginxHelmJenkinsValuesYaml = fileSystemUtils.readYaml(nginxHelmJenkinsValuesTmpFile)

        if (!config.features['secrets']['active']) {
            // External Secrets are not needed in example
            deleteFile nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/k8s/staging/external-secret.yaml'
            deleteFile nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/k8s/production/external-secret.yaml'
        }

        if (config['images']['nginx']) {
            log.debug("Setting custom nginx image as requested for nginx-helm-jenkins")
            def image = DockerImageParser.parse(config['images']['nginx'] as String)
            MapUtils.deepMerge([
                    image: [
                            registry: image.registry,
                            repository: image.repository,
                            tag: image.tag
                    ]
            ], nginxHelmJenkinsValuesYaml)
        }

        log.trace("nginx-helm-jenkins values yaml: ${nginxHelmJenkinsValuesYaml}")
        fileSystemUtils.writeYaml(nginxHelmJenkinsValuesYaml, nginxHelmJenkinsValuesTmpFile.toFile())
    }

    private void preparePetClinicRepos() {
        for (def repoInitAction : petClinicInitializationActions) {
            def tmpDir = repoInitAction.repo.getAbsoluteLocalRepoTmpDir()
            log.debug("Copying original petclinic files for petclinic repo: $tmpDir")
            fileSystemUtils.copyDirectory(remotePetClinicRepoTmpDir.toString(), tmpDir)
        }
    }

    private void prepareExerciseNginxValidationRepo() {
        if (!config['images']['nginx']) {
            return
        }

        def image = DockerImageParser.parse(config['images']['nginx'] as String)
        def valuesSharedTmpFile = Path.of nginxValidationInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), NGINX_VALIDATION_VALUES_PATH

        def yaml = YAMLMapper.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build()
                .writeValueAsString([
                        image: [
                                registry  : image.registry,
                                repository: image.repository,
                                tag       : image.tag,
                        ]
                ])
        // This file contains broken yaml, therefore we cannot parse it
        valuesSharedTmpFile.setText(valuesSharedTmpFile.text + yaml)
    }

    private void prepareExerciseBrokenApplicationRepo() {
        if (!config['images']['nginx']) {
            return
        }

        def image = DockerImageParser.parse(config['images']['nginx'] as String)
        def kubernetesResourcesPath = Path.of brokenApplicationInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), BROKEN_APPLICATION_RESOURCES_PATH

        fileSystemUtils.replaceFileContent(kubernetesResourcesPath.toString(), 'bitnami/nginx:1.25.1', "${image.getRegistryAndRepositoryAsString()}:$image.tag")
    }

    private void installArgoCd() {
        prepareArgoCdRepo()

        def namePrefix = config.application['namePrefix']
        log.debug("Creating repo credential secret that is used by argocd to access repos in SCM-Manager")
        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo 
        def repoTemplateSecretName = 'argocd-repo-creds-scmm'
        String scmmUrlForArgoCD = config.scmm["internal"] ? SCMM_URL_INTERNAL : ScmmRepo.createScmmUrl(config)
        k8sClient.createSecret('generic', repoTemplateSecretName, 'argocd',
                new Tuple2('url', scmmUrlForArgoCD),
                new Tuple2('username', "${namePrefix}gitops"),
                new Tuple2('password', password)
        )
        k8sClient.label('secret', repoTemplateSecretName,'argocd',
                new Tuple2(' argocd.argoproj.io/secret-type', 'repo-creds'))

        // Install umbrella chart from folder
        String umbrellaChartPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'argocd/')
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(
                Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), CHART_YAML_PATH))['dependencies']
        helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
        helmClient.dependencyBuild(umbrellaChartPath)
        helmClient.upgrade('argocd', umbrellaChartPath, [namespace: "${namePrefix}argocd"])
         
        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of values.yaml, because we don't want it to show in git repo 
        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        k8sClient.patch('secret', 'argocd-secret', 'argocd', 
                [stringData: ['admin.password': bcryptArgoCDPassword ] ])

        // Bootstrap root application
        k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml').toString())
        k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml').toString())

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', 'argocd', 
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))
    }

    protected void prepareArgoCdRepo() {
        def tmpHelmValues = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), HELM_VALUES_PATH)

        argocdRepoInitializationAction.initLocalRepo()

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in argocd repo to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(new File(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), SCMM_URL_INTERNAL, externalScmmUrl)
        }

        if (!config.application["remote"]) {
            log.debug("Setting argocd service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpHelmValues.toString(), "LoadBalancer", "NodePort")
        }

        if (config.features["argocd"]["url"]) {
            log.debug("Setting argocd url for notifications")
            fileSystemUtils.replaceFileContent(tmpHelmValues.toString(), 
                    "argocdUrl: https://localhost:9092", "argocdUrl: ${config.features["argocd"]["url"]}")
        }
        
        argocdRepoInitializationAction.repo.commitAndPush("Initial Commit")
    }

    private void deleteFile(String path) {
        boolean successfullyDeleted = new File(path).delete()
        if (!successfullyDeleted) {
            log.warn("Faild to delete file ${path}")
        }
    }

    private void deleteDir(String path) {
        boolean successfullyDeleted = new File(path).deleteDir()
        if (!successfullyDeleted) {
            log.warn("Faild to delete dir ${path}")
        }
    }

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmmRepoTarget) {
        new RepoInitializationAction(config, repoProvider.getRepo(scmmRepoTarget), localSrcDir)
    }

    private void replaceFileContentInYamls(File folder, String from, String to) {
        fileSystemUtils.getAllFilesFromDirectoryWithEnding(folder.absolutePath, ".yaml").forEach(file -> {
            fileSystemUtils.replaceFileContent(file.absolutePath, from, to)
        })
    }

    static class RepoInitializationAction {
        private ScmmRepo repo
        private String copyFromDirectory
        private Map config

        RepoInitializationAction(Map config, ScmmRepo repo, String copyFromDirectory) {
            this.config = config
            this.repo = repo
            this.copyFromDirectory = copyFromDirectory
        }

        /**
         * Clone repo from SCM and initialize it with default basic files. Afterwards we can edit these files.
         */
        void initLocalRepo() {
            repo.cloneRepo()
            repo.copyDirectoryContents(copyFromDirectory)

            repo.replaceTemplates(~/\.ftl/, [
                    namePrefix: config.application['namePrefix'] as String,
                    namePrefixForEnvVars: config.application['namePrefixForEnvVars'] as String,
                    images: config.images,
                    isRemote: config.application['remote'],
                    secrets: [
                            active: config.features['secrets']['active']
                    ],
                    scmm: [
                            baseUrl: config.scmm['internal'] ? 'http://scmm-scm-manager.default.svc.cluster.local/scm' : ScmmRepo.createScmmUrl(config),
                            host: config.scmm['internal'] ? 'scmm-scm-manager.default.svc.cluster.local' : config.scmm['host'],
                            protocol: config.scmm['internal'] ? 'http' : config.scmm['protocol'],
                    ]
            ])
        }

        ScmmRepo getRepo() {
            return repo
        }
    }
}
