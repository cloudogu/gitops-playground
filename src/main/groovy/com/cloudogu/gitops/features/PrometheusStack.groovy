package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.utils.*
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

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/prometheusstack/templates/prometheus-stack-helm-values.ftl.yaml"
    static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = "argocd/cluster-resources/apps/prometheusstack/templates/rbac/namespace-isolation-rbac.ftl.yaml"
    static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE = "argocd/cluster-resources/apps/prometheusstack/templates/netpols/prometheus-allow-scraping.ftl.yaml"

    private final Config config
    private final K8sClient k8sClient
    private final GitRepoFactory scmRepoProvider
    private final FileSystemUtils fileSystemUtils
    private final DeploymentStrategy deployer
    private final AirGappedUtils airGappedUtils
    private final GitHandler gitHandler
    private final String namespace

    PrometheusStack(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils,
            GitRepoFactory scmRepoProvider,
            GitHandler gitHandler
    ) {
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.deployer = deployer
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
        this.scmRepoProvider = scmRepoProvider
        this.gitHandler = gitHandler
        this.namespace = "${config.application.namePrefix}monitoring"
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

        def values = templateToMap(HELM_VALUES_PATH, templateModel)
        def helmConfig = config.features.monitoring.helm
        def mergedMap = MapUtils.deepMerge(helmConfig.values, values)

        // Create secrets imperatively here instead of values.yaml
        // because we don't want credentials to be visible in the Git repo
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

        if (!config.application.skipCrds) {
            def serviceMonitorCrdYaml
            if (config.application.mirrorRepos) {
                serviceMonitorCrdYaml = Path.of(
                        "${config.application.localHelmChartFolder}/${config.features.monitoring.helm.chart}/charts/crds/crds/crd-servicemonitors.yaml"
                ).toString()
            } else {
                serviceMonitorCrdYaml =
                        "https://raw.githubusercontent.com/prometheus-community/helm-charts/" +
                                "kube-prometheus-stack-${config.features.monitoring.helm.version}/" +
                                "charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml"
            }

            log.debug("Applying ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.\n" +
                    "Applying from path ${serviceMonitorCrdYaml}")
            k8sClient.applyYaml(serviceMonitorCrdYaml)
        }

        // Adjust GitOps repo content (RBAC / network policies / dashboards)
        GitRepo clusterResourcesRepo = scmRepoProvider.getRepo('argocd/cluster-resources', gitHandler.resourcesScm)
        clusterResourcesRepo.cloneRepo()

        if (config.application.namespaceIsolation || config.application.netpols) {
            for (String currentNamespace : config.application.namespaces.activeNamespaces) {

                if (config.application.namespaceIsolation) {
                    def rbacYaml = new TemplatingEngine().template(
                            new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
                            [
                                    namespace : currentNamespace,
                                    namePrefix: namePrefix,
                                    config    : config
                            ]
                    )
                    clusterResourcesRepo.writeFile(
                            "apps/prometheusstack/misc/rbac/${currentNamespace}.yaml",
                            rbacYaml
                    )
                }

                if (config.application.netpols) {
                    def netpolsYaml = new TemplatingEngine().template(
                            new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE),
                            [
                                    namespace : currentNamespace,
                                    namePrefix: namePrefix
                            ]
                    )

                    clusterResourcesRepo.writeFile(
                            "apps/prometheusstack/misc/netpols/${currentNamespace}.yaml",
                            netpolsYaml
                    )
                }
            }
        }

        // Remove dashboards for features that are not enabled
        cleanupUnusedDashboards(clusterResourcesRepo)
        clusterResourcesRepo.commitAndPush('Update Prometheus dashboards, RBAC and network policies.')

        // Deploy the Helm chart using the merged values
        def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

        if (config.application.mirrorRepos) {
            log.debug("Mirroring repos: Deploying Prometheus from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config.features.monitoring.helm as Config.HelmConfig)

            String prometheusVersion =
                    new YamlSlurper().parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
                            'Chart.yaml'))['version']

            deployer.deployFeature(
                    gitHandler.resourcesScm.repoUrl(repoNamespaceAndName),
                    'prometheusstack',
                    '.',
                    prometheusVersion,
                    namespace,
                    'kube-prometheus-stack',
                    tempValuesPath,
                    RepoType.GIT
            )
        } else {
            deployer.deployFeature(
                    helmConfig.repoURL,
                    'prometheusstack',
                    helmConfig.chart,
                    helmConfig.version,
                    namespace,
                    'kube-prometheus-stack',
                    tempValuesPath
            )
        }
    }

    private Map<String, Object> buildTemplateValues(Config config, String uid) {
        def model = [
                monitoring: [grafana: [host: config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl).host : ""]],
                namespaces: (config.application.namespaces.activeNamespaces ?: []) as LinkedHashSet<String>,
                scm      : scmConfigurationMetrics(),
                jenkins   : jenkinsConfigurationMetrics(),
                uid       : uid,
                config    : config,
                // Allow using static classes inside the templates
                statics   : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32)
                        .build()
                        .getStaticModels()
        ] as Map<String, Object>

        return model
    }

    private Map scmConfigurationMetrics() {
        def uri = gitHandler.resourcesScm.prometheusMetricsEndpoint()
        [
                protocol: uri?.scheme ?: "",
                host    : uri?.authority ?: "",
                path    : uri?.path ?: ""
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
        String uidRange = k8sClient.getAnnotation('namespace', namespace, 'openshift.io/sa.scc.uid-range')

        if (uidRange) {
            log.debug("found UID=${uidRange}")
            String uid = uidRange.split("/")[0]
            return uid
        } else {
            throw new RuntimeException("Could not find a valid UID! Really running on OpenShift?")
        }
    }

    protected void cleanupUnusedDashboards(GitRepo clusterResourcesRepo) {
        String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
        String dashboardRoot = "${repoRoot}/apps/prometheusstack/misc/dashboard"

        if (!config.features.ingressNginx.active) {
            fileSystemUtils.deleteFile("${dashboardRoot}/ingress-nginx-dashboard.yaml")
            fileSystemUtils.deleteFile("${dashboardRoot}/ingress-nginx-dashboard-requests-handling.yaml")
        }

        if (!config.jenkins.active) {
            fileSystemUtils.deleteFile("${dashboardRoot}/jenkins-dashboard.yaml")
        }

        if (!config.scm.scmManager?.url) {
            fileSystemUtils.deleteFile("${dashboardRoot}/scmm-dashboard.yaml")
        }
    }

    @Override
    String getNamespace() {
        return namespace
    }

    @Override
    K8sClient getK8sClient() {
        return k8sClient
    }

    @Override
    Config getConfig() {
        return config
    }
}
