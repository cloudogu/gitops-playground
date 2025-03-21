package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.FeatureWithImage
import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
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

    static final String HELM_VALUES_PATH = "applications/cluster-resources/monitoring/prometheus-stack-helm-values.ftl.yaml"
    static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = 'applications/cluster-resources/monitoring/rbac/namespace-isolation-rbac.ftl.yaml'
    static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE = 'applications/cluster-resources/monitoring/netpols/prometheus-allow-scraping.ftl.yaml'

    String namespace = 'monitoring'
    Config config
    K8sClient k8sClient

    ScmmRepoProvider scmmRepoProvider
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private AirGappedUtils airGappedUtils

    PrometheusStack(
            Config config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils,
            ScmmRepoProvider scmmRepoProvider
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

        def templatedMap = templateToMap(HELM_VALUES_PATH, [
                namePrefix        : namePrefix,
                podResources      : config.application.podResources,
                monitoring        : [
                        grafanaEmailFrom: config.features.monitoring.grafanaEmailFrom,
                        grafanaEmailTo  : config.features.monitoring.grafanaEmailTo,
                        grafana         : [
                                // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                                host: config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl).host : ""
                        ]
                ],
                remote            : config.application.remote,
                skipCrds          : config.application.skipCrds,
                namespaceIsolation: config.application.namespaceIsolation,
                namespaces        : config.application.activeNamespaces,
                mail              : [
                        active      : config.features.mail.active,
                        smtpAddress : config.features.mail.smtpAddress,
                        smtpPort    : config.features.mail.smtpPort,
                        smtpUser    : config.features.mail.smtpUser,
                        smtpPassword: config.features.mail.smtpPassword
                ],
                scmm              : getScmmConfiguration(),
                jenkins           : getJenkinsConfiguration(),
                config            : config,
                // Allow for using static classes inside the templates
                statics           : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels(),
                uid               : config.application.openshift ? findValidOpenShiftUid()  : ''
        ])


        def helmConfig = config.features.monitoring.helm
        def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)

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
            ScmmRepo clusterResourcesRepo = scmmRepoProvider.getRepo('argocd/cluster-resources')
            clusterResourcesRepo.cloneRepo()
            for (String currentNamespace : config.application.activeNamespaces) {

                if (config.application.namespaceIsolation) {
                    def rbacYaml = new TemplatingEngine().template(new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
                            [namespace : currentNamespace,
                             namePrefix: namePrefix,
                             config: config])
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
                    "${scmmUri}/repo/${repoNamespaceAndName}",
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


    private Map getScmmConfiguration() {
        // Note that URI.resolve() seems to throw away the existing path. So we create a new URI object.
        URI uri = new URI("${scmmUri}/api/v2/metrics/prometheus")

        return [
                protocol: uri.scheme,
                host    : uri.authority,
                path    : uri.path
        ]
    }

    private URI getScmmUri() {
        if (config.scmm.internal) {
            new URI('http://scmm-scm-manager.default.svc.cluster.local/scm')
        } else {
            new URI("${config.scmm.url}")
        }
    }

    private Map getJenkinsConfiguration() {
        String path = 'prometheus'
        URI uri
        if (config.jenkins.internal) {
            uri = new URI("http://jenkins.default.svc.cluster.local/${path}")
        } else {
            uri = new URI("${config.jenkins.url}/${path}")
        }

        return [
                metricsUsername: config.jenkins.metricsUsername,
                protocol       : uri.scheme,
                host           : uri.authority,
                path           : uri.path
        ]
    }
}
