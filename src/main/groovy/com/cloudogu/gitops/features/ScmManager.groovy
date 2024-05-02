package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import retrofit2.Response

import java.nio.file.Path

@Slf4j
@Singleton
@Order(80)
class ScmManager extends Feature {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"
    static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = '3rd-party-dependencies'

    private Map config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private String gitName
    private String gitEmail
    private ScmmRepoProvider repoProvider
    private RepositoryApi repositoryApi
    private String gitOpsUsername

    ScmManager(
            Configuration config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            ScmmRepoProvider repoProvider,
            RepositoryApi repositoryApi,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer
    ) {
        this.config = config.getConfig()
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.repoProvider = repoProvider
        this.repositoryApi = repositoryApi
        this.deployer = deployer
        this.gitName = this.config['application']['gitName']
        this.gitEmail = this.config['application']['gitEmail']
        this.gitOpsUsername = "${this.config.application['namePrefix']}gitops"
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {

        if (config.scmm['internal']) {
            def helmConfig = config['scmm']['helm']

            def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [
                    host  : config.scmm['ingress'],
                    remote: config.application['remote'],
                    username:  config.scmm['username'],
                    password: config.scmm['password']
            ]).toPath()

            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'scm-manager',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    'default',
                    'scmm',
                    tmpHelmValues
            )
        }

        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [

                GIT_COMMITTER_NAME           : config.application['gitName'],
                GIT_COMMITTER_EMAIL          : config.application['gitEmail'],
                GIT_AUTHOR_NAME              : config.application['gitName'],
                GIT_AUTHOR_EMAIL             : config.application['gitEmail'],
                GITOPS_USERNAME              : gitOpsUsername,
                TRACE                        : config.application['trace'],
                SCMM_URL                     : config.scmm['url'],
                SCMM_USERNAME                : config.scmm['username'],
                SCMM_PASSWORD                : config.scmm['password'],
                JENKINS_URL                  : config.jenkins['url'],
                JENKINS_URL_FOR_SCMM         : config.jenkins['urlForScmm'],
                SCMM_URL_FOR_JENKINS         : config.scmm['urlForJenkins'],
                // Used indirectly in utils.sh 😬
                REMOTE_CLUSTER               : config.application['remote'],
                INSTALL_ARGOCD               : config.features['argocd']['active'],
                SPRING_BOOT_HELM_CHART_COMMIT: config.repositories['springBootHelmChart']['ref'],
                SPRING_BOOT_HELM_CHART_REPO  : config.repositories['springBootHelmChart']['url'],
                GITOPS_BUILD_LIB_REPO        : config.repositories['gitopsBuildLib']['url'],
                CES_BUILD_LIB_REPO           : config.repositories['cesBuildLib']['url'],
                NAME_PREFIX                  : config.application['namePrefix'],
                INSECURE                     : config.application['insecure'],
        ])

        if (config.application['airGapped']) {
            log.debug("Air-gapped mode: Preparing mirrored repos")
            
            // In air-gapped mode, the chart's dependencies can't be resolved.
            // As helm does not provide an option for changing them interactively, we push the charts into a separate repo.
            // There, we remove all dependencies, and deploy them separately.
            
            // TODO only when monitoring active!
            
            def helmConfig = config['features']['monitoring']['helm']
            // TODO move to AppConfigurator
            if (!helmConfig['localFolder']) {
                // This should only happen when run outside the image, i.e. during development
                throw new RuntimeException("Missing config for localFolder of helm chart ${helmConfig['chart']}.\n " +
                        "Either run inside the official container image or setting env var" +
                        "KUBE_PROM_STACK_HELMCHART_PATH='charts/kube-prometheus-stack' after running this:\n" +
                        "helm repo add prometheus-community ${helmConfig['repoURL']}\n" +
                        "helm pull --untar --untardir charts prometheus-community/${helmConfig['chart']} --version ${helmConfig['version']}")
            }

            preparePrometheusRepo()

            prepareGrafanaRepo()
        }
    }

    protected void preparePrometheusRepo() {
        def helmConfig = config['features']['monitoring']['helm']
        def namespace = NAMESPACE_3RD_PARTY_DEPENDENCIES
        String repoName = helmConfig['chart']
        def ys = new YamlSlurper()
        
        createRepo(namespace, repoName, "Mirror of Helm chart $repoName from ${helmConfig['repoURL']}")

        ScmmRepo prometheusRepo = repoProvider.getRepo("$namespace/${repoName}")
        prometheusRepo.cloneRepo()
        prometheusRepo.copyDirectoryContents(helmConfig['localFolder'] as String)

        log.debug("Preparing repo ${prometheusRepo.scmmRepoTarget} for air-gapped use: Removing external dependencies.")
        def prometheusChartYamlPath = Path.of(prometheusRepo.absoluteLocalRepoTmpDir, 'Chart.yaml')
        Map prometheusChartYaml = ys.parse(prometheusChartYamlPath) as Map
        (prometheusChartYaml['dependencies'] as List).removeAll { it['name'] != 'crds' }
        fileSystemUtils.writeYaml(prometheusChartYaml, prometheusChartYamlPath.toFile())

        fileSystemUtils.deleteFilesExcept(new File(prometheusRepo.absoluteLocalRepoTmpDir, 'charts'), 'crds')
        
        // Chart.lock contains pinned dependencies and digest. 
        // Both have to go because they no longer match after changes made above
        new File(prometheusRepo.absoluteLocalRepoTmpDir, 'Chart.lock').delete()

        prometheusRepo.commitAndPush("Chart ${prometheusChartYaml.name}, version: ${prometheusChartYaml.version}\n\n" +
                "Source: ${helmConfig['repoURL']}\n" +
                "Dependencies removed to run in air-gapped environments", prometheusChartYaml.version as String)
    }

    protected void prepareGrafanaRepo() {
        def helmConfig = config['features']['monitoring']['helm']
        def namespace = NAMESPACE_3RD_PARTY_DEPENDENCIES
        def repoName = 'grafana'
        def ys = new YamlSlurper()
        
        def prometheusChartYamlPath = Path.of(helmConfig['localFolder'] as String, 'Chart.yaml')
        Map grafanaDependencyFromPrometheusChart = ys.parse(prometheusChartYamlPath)['dependencies'].find { it['name'] == 'grafana' } as Map
        def repositoryUrl = grafanaDependencyFromPrometheusChart.repository

        createRepo(namespace, repoName, "Mirror of Helm chart ${repoName} from ${repositoryUrl}")

        ScmmRepo grafanaRepo =  repoProvider.getRepo("$namespace/${repoName}")
        grafanaRepo.cloneRepo()
        grafanaRepo.copyDirectoryContents("${helmConfig['localFolder']}/charts/$repoName")

        def grafanaChartYamlPath = Path.of(grafanaRepo.absoluteLocalRepoTmpDir, 'Chart.yaml')
        Map grafanaChartYaml = ys.parse(grafanaChartYamlPath) as Map

        grafanaRepo.commitAndPush("Chart ${repoName}, version: ${grafanaChartYaml.version}\n\n" +
                "Source: ${repositoryUrl}", grafanaChartYaml.version as String)
    }

    void createRepo(String namespace, String repoName, String description) {
        def repo = new Repository(namespace, repoName, description)
        def createResponse = repositoryApi.create(repo, true).execute()
        handleResponse(createResponse, repo)
        
        def permission = new Permission(gitOpsUsername, Permission.Role.WRITE)
        def permissionResponse = repositoryApi.createPermission(namespace, repoName, permission).execute()
        handleResponse(permissionResponse, permission, "for repo $namespace/$repoName")
    }

    protected void handleResponse(Response<Void> response, Object body, String additionalMessage = '') {
        if (response.code() == 409) {
            // Here, we could consider sending another request for changing the existing object to become proper idempotent
            log.debug("${body.class.simpleName} already exists ${additionalMessage}, ignoring: ${body}")
        } else if (response.code() != 201) {
            throw new RuntimeException("Could not create ${body.class.simpleName} ${additionalMessage}.\n${body}\n" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
    }
}
