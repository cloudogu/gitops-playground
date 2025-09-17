package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.gitHandling.local.LocalRepository
import com.cloudogu.gitops.gitHandling.local.LocalRepositoryFactory
import com.cloudogu.gitops.utils.*
import com.cloudogu.gitops.gitHandling.providers.ScmUrlResolver
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType

@Slf4j
@Singleton
@Order(300)
class PrometheusStack extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/monitoring/prometheus-stack-helm-values.ftl.yaml"
    static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = 'applications/cluster-resources/monitoring/rbac/namespace-isolation-rbac.ftl.yaml'
    static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE = 'applications/cluster-resources/monitoring/netpols/prometheus-allow-scraping.ftl.yaml'

    String namespace = "${config.application.namePrefix}monitoring"
    Config config
    K8sClient k8sClient

    LocalRepositoryFactory scmmRepoProvider
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils

    PrometheusStack(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils,
            LocalRepositoryFactory scmmRepoProvider
    ) {
        this.deployer = deployer
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
        this.scmmRepoProvider = scmmRepoProvider
    }

    @Override
    boolean isEnabled() {
        return config.features.monitoring.active
    }

    @Override
    void enable() {
        def namePrefix = config.application.namePrefix

        String uid = ""
        if (config.application.openshift) {
            uid = findValidOpenShiftUid()
        }

        Map<String, Object> templateModel = buildTemplateValues(config, uid)

        def values    = templateToMap(HELM_VALUES_PATH, templateModel)

        def helmConfig = config.features.monitoring.helm
        def mergedMap = MapUtils.deepMerge(helmConfig.values, values)

        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
        k8sClient.createSecret(
                'generic',
                'prometheus-metrics-creds-scmm',
                namespace,
                new Tuple2('password', config.application.password)
        )

        k8sClient.createSecret(
                'generic',
                'prometheus-metrics-creds-jenkins',
                namespace,
                new Tuple2('password', config.jenkins.metricsPassword),
        )

        if (config.features.mail.smtpUser || config.features.mail.smtpPassword) {
            k8sClient.createSecret(
                    'generic',
                    'grafana-email-secret',
                    namespace,
                    new Tuple2('user', config.features.mail.smtpUser),
                    new Tuple2('password', config.features.mail.smtpPassword)
            )
        }

        if (config.application.namespaceIsolation || config.application.netpols) {
            LocalRepository clusterResourcesRepo = scmmRepoProvider.getRepo('argocd/cluster-resources', config.multiTenant.useDedicatedInstance)
            clusterResourcesRepo.cloneRepo()
            for (String currentNamespace : config.application.namespaces.activeNamespaces) {

                if (config.application.namespaceIsolation) {
                    def rbacYaml = new TemplatingEngine().template(new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
                            [namespace : currentNamespace,
                             namePrefix: namePrefix,
                             config    : config])
                    clusterResourcesRepo.writeFile("misc/monitoring/rbac/${currentNamespace}.yaml", rbacYaml)
                }

                if (config.application.netpols) {
                    def netpolsYaml = new TemplatingEngine().template(new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE),
                            [namespace : currentNamespace,
                             namePrefix: namePrefix])

                    clusterResourcesRepo.writeFile("misc/monitoring/netpols/${currentNamespace}.yaml", netpolsYaml)
                }
            }
            clusterResourcesRepo.commitAndPush('Adding namespace-isolated RBAC and network policies if enabled.')
        }

        def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

        if (config.application.mirrorRepos) {
            log.debug("Mirroring repos: Deploying prometheus from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config.features.monitoring.helm as Config.HelmConfig)

            String prometheusVersion =
                    new YamlSlurper().parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    ScmUrlResolver.scmmRepoUrl(config, repoNamespaceAndName),
                    'prometheusstack',
                    '.',
                    prometheusVersion,
                    namespace,
                    'kube-prometheus-stack',
                    tempValuesPath, RepoType.GIT)
        } else {

            deployer.deployFeature(
                    helmConfig.repoURL,
                    'prometheusstack',
                    helmConfig.chart,
                    helmConfig.version,
                    namespace,
                    'kube-prometheus-stack',
                    tempValuesPath)
        }
    }

    private Map<String, Object> buildTemplateValues(Config config, String uid){
        def model = [
                monitoring: [grafana: [host: config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl).host : ""]],
                namespaces: (config.application.namespaces.activeNamespaces ?: []) as LinkedHashSet<String>,
                scmm      : scmConfigurationMetrics(),
                jenkins   : jenkinsConfigurationMetrics(),
                uid       : uid,
                config    : config,
                // Allow for using static classes inside the templates
                statics : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ] as Map<String, Object>

        return model
    }

    private Map scmConfigurationMetrics() {
        def uri = ScmUrlResolver.scmmBaseUri(config).resolve("api/v2/metrics/prometheus")
        [
                protocol: uri.scheme ?: "",
                host    : uri.authority ?: "",
                path    : uri.path ?: ""
        ]
    }

    private Map jenkinsConfigurationMetrics() {
        def uri = baseUriJenkins(config).resolve("prometheus")
        [
                metricsUsername: config.jenkins.metricsUsername ?: "",
                protocol       : uri.scheme ?: "",
                host           : uri.authority ?: "",
                path           : uri.path ?: ""
        ]
    }

    private static URI baseUriJenkins(Config config) {
        if (config.jenkins.internal) {
            return new URI("http://jenkins.${config.application.namePrefix}jenkins.svc.cluster.local/")
        }
        def urlString = config.jenkins?.url?.strip() ?: ""
        if (!urlString) {
            throw new IllegalArgumentException("config.jenkins.url must be set when config.jenkins.internal = false")
        }
        def url = URI.create(urlString)
        return url.toString().endsWith("/") ? url : URI.create(url.toString() + "/")
    }

    private String findValidOpenShiftUid() {
        String uidRange = k8sClient.getAnnotation('namespace', 'monitoring', 'openshift.io/sa.scc.uid-range')

        if (uidRange) {
            log.debug("found UID=${uidRange}")
            String uid = uidRange.split("/")[0]
            return uid
        } else {
            throw new RuntimeException("Could not find a valid UID! Really running on openshift?")
        }
    }
}