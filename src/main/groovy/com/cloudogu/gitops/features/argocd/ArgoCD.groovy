package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.utils.*
import freemarker.template.DefaultObjectWrapperBuilder
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
    static final String OPERATOR_CONFIG_PATH = 'operator/argocd.yaml'
    static final String OPERATOR_RBAC_PATH = 'operator/rbac'
    static final String CHART_YAML_PATH = 'argocd/Chart.yaml'
    static final String SCMM_URL_INTERNAL = "http://scmm-scm-manager.default.svc.cluster.local/scm"
    static final String MONITORING_RESOURCES_PATH = '/misc/monitoring/'

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
        log.debug("Cloning Repositories")
        cloneRemotePetclinicRepo()
        
        gitRepos.forEach( repoInitializationAction -> {
            repoInitializationAction.initLocalRepo()
        })

        prepareGitOpsRepos()

        prepareApplicationNginxHelmJenkins()

        preparePetClinicRepos()

        gitRepos.forEach( repoInitializationAction -> {
            repoInitializationAction.repo.commitAndPush("Initial Commit")
        })

        log.debug("Installing Argo CD")
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

        if(config.features['argocd']['operator']) {
            log.debug("Deleting unnecessary argocd (argocd helm variant) folder from argocd repo: ${argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/argocd'
            log.debug("Deleting unnecessary namespaces resources from clusterResources repo: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteFile clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/misc/namespaces.yaml'
        } else {
            log.debug("Deleting unnecessary operator (argocd operator variant) folder from argocd repo: ${argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/operator'
        }

        if (!config.features['secrets']['active']) {
            log.debug("Deleting unnecessary secrets folder from cluster resources: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteDir clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/misc/secrets'
        }

        if (!config.features['monitoring']['active']) {
            log.debug("Deleting unnecessary monitoring folder from cluster resources: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            deleteDir clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + MONITORING_RESOURCES_PATH
        } else if (!config.features['ingressNginx']['active']) {
            deleteFile clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + MONITORING_RESOURCES_PATH + 'ingress-nginx-dashboard.yaml'
            deleteFile clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + MONITORING_RESOURCES_PATH + 'ingress-nginx-dashboard-requests-handling.yaml'
        }

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in gitops repos to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(new File(clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), SCMM_URL_INTERNAL, externalScmmUrl)
            replaceFileContentInYamls(new File(exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), SCMM_URL_INTERNAL, externalScmmUrl)
        }

        fileSystemUtils.copyDirectory("${fileSystemUtils.rootDir}/applications/argocd/nginx/helm-umbrella",
                Path.of(exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'apps/nginx-helm-umbrella/').toString())
        exampleAppsInitializationAction.replaceTemplates()
    }

    private void prepareApplicationNginxHelmJenkins() {
        if (!config.features['secrets']['active']) {
            // External Secrets are not needed in example
            deleteFile nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/k8s/staging/external-secret.yaml'
            deleteFile nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/k8s/production/external-secret.yaml'
        }
    }

    private void preparePetClinicRepos() {
        for (def repoInitAction : petClinicInitializationActions) {
            def tmpDir = repoInitAction.repo.getAbsoluteLocalRepoTmpDir()
            
            log.debug("Copying original petclinic files for petclinic repo: $tmpDir")
            fileSystemUtils.copyDirectory(remotePetClinicRepoTmpDir.toString(), tmpDir)
            fileSystemUtils.deleteEmptyFiles(Path.of(tmpDir), ~/k8s\/.*\.yaml/)
            
            new TemplatingEngine().template(
                    new File("${fileSystemUtils.getRootDir()}/applications/argocd/petclinic/Dockerfile.ftl"),
                    new File("${tmpDir}/Dockerfile"),
                    [ baseImage: config['images']['petclinic'] as String ]
            )
        }
    }

    private void installArgoCd() {
        
        prepareArgoCdRepo()

        def namePrefix = config.application['namePrefix']
        def namespaceList = getNamespaceList()
        
        log.debug("Creating namespaces")
        k8sClient.createNamespaces(namespaceList)

        createMonitoringCrd()

        log.debug("Creating repo credential secret that is used by argocd to access repos in SCM-Manager")
        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo 
        def repoTemplateSecretName = 'argocd-repo-creds-scmm'
        String scmmUrlForArgoCD = config.scmm["internal"] ? SCMM_URL_INTERNAL : ScmmRepo.createScmmUrl(config)
        k8sClient.createSecret('generic', repoTemplateSecretName, 'argocd',
                new Tuple2('url', scmmUrlForArgoCD),
                new Tuple2('username', "${namePrefix}gitops"),
                new Tuple2('password', config.scmm['password'])
        )

        k8sClient.label('secret', repoTemplateSecretName,'argocd',
                new Tuple2(' argocd.argoproj.io/secret-type', 'repo-creds'))

        if (config.features['mail']['smtpUser'] || config.features['mail']['smtpPassword']) {
            k8sClient.createSecret(
                    'generic',
                    'argocd-notifications-secret',
                    'argocd',
                    new Tuple2('email-username', config.features['mail']['smtpUser']),
                    new Tuple2('email-password', config.features['mail']['smtpPassword'])
            )
        }

        if(config.features['argocd']['operator']) {
            deployWithOperator("argocd")
        } else {
            deployWithHelm(namePrefix, "argocd")
        }

        // Bootstrap root application
        k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml').toString())
        k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml').toString())
    }

    private static List<String> getNamespaceList() {
        def namespaceList = ["argocd", "monitoring", "ingress-nginx", "example-apps-staging", "example-apps-production", "secrets"]
        return namespaceList
    }

    private void deployWithHelm(namePrefix, String argocdNamespace) {
        // Install umbrella chart from folder
        String umbrellaChartPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'argocd/')
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(
                Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), CHART_YAML_PATH))['dependencies']
        helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
        helmClient.dependencyBuild(umbrellaChartPath)
        helmClient.upgrade('argocd', umbrellaChartPath, [namespace: "${namePrefix}${argocdNamespace}"])

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', 'argocd',
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))

        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of values.yaml, because we don't want it to show in git repo
        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        k8sClient.patch('secret', 'argocd-secret', 'argocd',
                [stringData: ['admin.password': bcryptArgoCDPassword ] ])
    }

    private void deployWithOperator(String argocdNamespace) {
        // Apply argocd yaml from operator folder
        String argocdConfigPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), OPERATOR_CONFIG_PATH)
        k8sClient.applyYaml(argocdConfigPath)

        // ArgoCD is not installed until the ArgoCD-Operator did his job.
        // This can take some time, so we wait for the status of the custom resource to become "Available"
        k8sClient.waitForResourcePhase("argocd", "argocd", argocdNamespace, "Available")

        if(!config.application['openshift']) {
            // We need to patch the NodePrt of the Service, because the operator only supports setting type: NodePort but not the port itself
            log.debug("Patching NodePorts for 'argocd-server' Service in namespace '{}' to HTTP: 9092 and HTTPS: 9093", argocdNamespace);
            k8sClient.patchServiceNodePort("argocd-server", argocdNamespace, "http", 9092)
            k8sClient.patchServiceNodePort("argocd-server", argocdNamespace, "https", 9093)
        }

        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of operator/argocd.yaml, because we don't want it to show in git repo
        // The Operator uses an extra secret to store the admin Password, which is not bcrypted
        k8sClient.patch('secret', 'argocd-cluster', argocdNamespace,
                [stringData: ['admin.password': password ] ])

        log.debug("Updating managed namespaces in ArgoCD configuration secret.")
        // The ArgoCD instance installed via an operator only manages its deployment namespace.
        // To manage additional namespaces, we need to update the 'argocd-default-cluster-config' secret with all managed namespaces.
        def namespaceList = getNamespaceList()
        k8sClient.patch('secret', 'argocd-default-cluster-config', argocdNamespace,
                [stringData: ['namespaces': namespaceList.join(',') ] ])

        log.debug("Add RBAC permissions for ArgoCD in all managed namespaces.")
        // Apply rbac yamls from operator/rbac folder
        String argocdRbacPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), OPERATOR_RBAC_PATH)
        k8sClient.applyYaml(argocdRbacPath)
    }

    protected void createMonitoringCrd() {
        if (config['features']['monitoring']['active']) {
            if (!config['application']['skipCrds']) {
                def serviceMonitorCrdYaml
                if (config.application['mirrorRepos']) {
                    serviceMonitorCrdYaml = Path.of(
                            "${config.application['localHelmChartFolder']}/${config['features']['monitoring']['helm']['chart']}/charts/crds/crds/crd-servicemonitors.yaml"
                    ).toString()
                } else {
                    serviceMonitorCrdYaml =
                            "https://raw.githubusercontent.com/prometheus-community/helm-charts/" +
                                    "kube-prometheus-stack-${config['features']['monitoring']['helm']['version']}/" +
                                    "charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml"
                }

                log.debug("Applying ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.\n" +
                        "Applying from path ${serviceMonitorCrdYaml}")
                k8sClient.applyYaml(serviceMonitorCrdYaml)
            }
        }
    }

    protected void prepareArgoCdRepo() {
        String argocdConfigPath = this.config.features['argocd']['operator'] ? OPERATOR_CONFIG_PATH : HELM_VALUES_PATH;
        def argocdConfigFile = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), argocdConfigPath)

        argocdRepoInitializationAction.initLocalRepo()

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in argocd repo to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(new File(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), SCMM_URL_INTERNAL, externalScmmUrl)
        }

        if (!config.application["remote"]) {
            log.debug("Setting argocd service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(argocdConfigFile.toString(), "LoadBalancer", "NodePort")
        }

        if (config.features["argocd"]["url"]) {
            log.debug("Setting argocd url for notifications")
            fileSystemUtils.replaceFileContent(argocdConfigFile.toString(),
                    "argocdUrl: https://localhost:9092", "argocdUrl: ${config.features["argocd"]["url"]}")
        }

        if (!config.application["netpols"]) {
            log.debug("Deleting argocd netpols.")
            deleteFile argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/argocd/templates/allow-namespaces.yaml'
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
            replaceTemplates()
        }

        void replaceTemplates() {
            repo.replaceTemplates(~/\.ftl/, [
                    namePrefix          : config.application['namePrefix'] as String,
                    namePrefixForEnvVars: config.application['namePrefixForEnvVars'] as String,
                    podResources        : config.application['podResources'],
                    images              : config.images,
                    nginxImage          : config.images['nginx'] ? DockerImageParser.parse(config.images['nginx'] as String) : null,
                    isRemote            : config.application['remote'],
                    isInsecure          : config.application['insecure'],
                    isOpenshift         : config.application['openshift'],
                    urlSeparatorHyphen  : config.application['urlSeparatorHyphen'],
                    mirrorRepos         : config.application['mirrorRepos'],
                    skipCrds            : config.application['skipCrds'],
                    netpols             : config.application['netpols'],
                    argocd              : [
                            // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                            host: config.features['argocd']['url'] ? new URL(config.features['argocd']['url'] as String).host : "",
                            env : config.features['argocd']['env'],
                            isOperator   : config.features['argocd']['operator'],
                            emailFrom    : config.features['argocd']['emailFrom'],
                            emailToUser  : config.features['argocd']['emailToUser'],
                            emailToAdmin : config.features['argocd']['emailToAdmin'],
                            resourceInclusionsCluster : getResourceInclusionsCluster()
                    ],
                    registry : [
                            twoRegistries: config.registry['twoRegistries']
                    ],
                    monitoring          : [
                            grafana: [
                                    url: config.features['monitoring']['grafanaUrl'] ? new URL(config.features['monitoring']['grafanaUrl'] as String) : null,
                            ],
                            active: config['features']['monitoring']['active']
                    ],
                    mail: [
                            active: config.features['mail']['active'],
                            smtpAddress : config.features['mail']['smtpAddress'],
                            smtpPort : config.features['mail']['smtpPort'],
                            smtpUser : config.features['mail']['smtpUser'],
                            smtpPassword : config.features['mail']['smtpPassword']
                    ],
                    secrets             : [
                            active: config.features['secrets']['active'],
                            vault : [
                                    url: config.features['secrets']['vault']['url'] ? new URL(config.features['secrets']['vault']['url'] as String) : null,
                            ],
                    ],
                    scmm                : [
                            baseUrl : config.scmm['internal'] ? 'http://scmm-scm-manager.default.svc.cluster.local/scm' : ScmmRepo.createScmmUrl(config),
                            host    : config.scmm['internal'] ? 'scmm-scm-manager.default.svc.cluster.local' : config.scmm['host'],
                            protocol: config.scmm['internal'] ? 'http' : config.scmm['protocol'],
                    ],
                    jenkins             : [
                            mavenCentralMirror  : config.jenkins['mavenCentralMirror'],
                    ],
                    exampleApps         : [
                            petclinic: [
                                    baseDomain: config.features['exampleApps']['petclinic']['baseDomain']
                            ],
                            nginx    : [
                                    baseDomain: config.features['exampleApps']['nginx']['baseDomain']
                            ],
                    ],
                    config: config,
                    // Allow for using static classes inside the templates
                    statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
            ])
        }

        private String getResourceInclusionsCluster() {
            // Return early if NOT deploying via operator
            if(config.features['argocd']['operator'] == false) {
                return ""
            }

            try {
                // Attempt to get the internal cluster URL from the config or environment variables
                return config.application['internalKubernetesApiUrl'] ?: K8sClient.getInternalKubernetesApiServerAddress();
            } catch (RuntimeException e) {
                // Extend the exception message and throw a new RuntimeException
                String extendedMessage = "Could not determine 'resourceInclusions.cluster' which is needed with argocd.operator=true. " +
                        "Try setting 'application.internalKubernetesApiUrl' in the config to manually override.";
                throw new RuntimeException(extendedMessage, e);
            }
        }

        ScmmRepo getRepo() {
            return repo
        }
    }
}
