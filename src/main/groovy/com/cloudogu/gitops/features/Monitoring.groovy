package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.*
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import java.nio.file.Path

@Slf4j
@Singleton
@Order(300)
@CompileStatic
class Monitoring extends Feature implements FeatureWithImage {

    static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml'
    static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = 'argocd/cluster-resources/apps/monitoring/templates/rbac/namespace-isolation-rbac.ftl.yaml'
    static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE = 'argocd/cluster-resources/apps/monitoring/templates/netpols/prometheus-allow-scraping.ftl.yaml'

    String namespace = "${config.application.namePrefix}monitoring"
    Config config
    K8sClient k8sClient

    private GitRepoFactory scmRepoProvider

    Monitoring(
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
    }

    @Override
    boolean isEnabled() {
        return config.features.monitoring.active
    }

    @Override
    void enable() {
        String uid = ''
        if (config.application.openshift) {
            uid = findValidOpenShiftUid()
        }

        addHelmValuesData('monitoring',  [grafana: [host: config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl).host : '']])
        addHelmValuesData('namespaces', (config.application.namespaces.activeNamespaces ?: []) as LinkedHashSet<String>)
        addHelmValuesData('scm', scmConfigurationMetrics())
        addHelmValuesData('jenkins', jenkinsConfigurationMetrics())
        addHelmValuesData('uid', uid)

        // Create secrets imperatively here instead of values.yaml, because we don't want credentials to be visible in the Git repo
        setupMonitoringSecrets()
        createMonitoringCrd()

        GitRepo clusterResourcesRepo = scmRepoProvider.getRepo('argocd/cluster-resources', this.gitHandler.resourcesScm)
        clusterResourcesRepo.cloneRepo()

        if (config.application.namespaceIsolation || config.application.netpols) {
            if (config.application.namespaceIsolation) { generateNamespaceIsolationRBAC(clusterResourcesRepo) }
            if (config.application.netpols) { generateNetpols(clusterResourcesRepo) }
        }

        // Remove dashboards for features that are not enabled
        cleanupUnusedDashboards(clusterResourcesRepo)

        clusterResourcesRepo.commitAndPush('Update Prometheus dashboards, RBAC and network policies.')
        deployHelmChart('prometheusstack', 'kube-prometheus-stack', namespace, config.features.monitoring.helm, HELM_VALUES_PATH, config)
    }

    private void setupMonitoringSecrets() {
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
    }

    private void generateNamespaceIsolationRBAC(GitRepo repo) {
        for (String currentNamespace : config.application.namespaces.activeNamespaces) {
            String rbacYaml = new TemplatingEngine().template(new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
                    [namespace : currentNamespace,
                     namePrefix: config.application.namePrefix,
                     config    : config,])
            repo.writeFile(
                    "apps/monitoring/misc/rbac/${currentNamespace}.yaml",
                    rbacYaml
            )
        }
    }

    private void generateNetpols(GitRepo repo) {
        for (String currentNamespace : config.application.namespaces.activeNamespaces) {
            String netpolsYaml = new TemplatingEngine().template(new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE),
                    [namespace : currentNamespace,
                     namePrefix: config.application.namePrefix,])

            repo.writeFile(
                    "apps/monitoring/misc/netpols/${currentNamespace}.yaml",
                    netpolsYaml
            )
        }
    }

    private Map scmConfigurationMetrics() {
        URI uri = this.gitHandler.resourcesScm.prometheusMetricsEndpoint()
        return [
                protocol: uri?.scheme ?: '',
                host    : uri?.authority ?: '',
                path    : uri?.path ?: '',
        ]
    }

    protected void createMonitoringCrd() {
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
    }

    private Map jenkinsConfigurationMetrics() {
        URI uri = baseUriJenkins(config).resolve('prometheus')
        return [
                metricsUsername: config.jenkins.metricsUsername ?: '',
                protocol       : uri.scheme ?: '',
                host           : uri.authority ?: '',
                path           : uri.path ?: '',
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
            String uid = uidRange.split('/')[0]
            return uid
        } else {
            throw new RuntimeException("Could not find a valid UID! Really running on OpenShift?")
        }
    }

    protected void cleanupUnusedDashboards(GitRepo clusterResourcesRepo) {
        String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
        String dashboardRoot = "${repoRoot}/apps/prometheusstack/misc/dashboard"

        if (!config.features.ingress.active) {
            fileSystemUtils.deleteFile("${dashboardRoot}/traefik-dashboard.yaml")
            fileSystemUtils.deleteFile("${dashboardRoot}/traefik-dashboard-requests-handling.yaml")
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
