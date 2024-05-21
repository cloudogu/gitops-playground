package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType

@Slf4j
@Singleton
@Order(300)
class PrometheusStack extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/monitoring/prometheus-stack-helm-values.ftl.yaml"
    
    private Map config
    private boolean remoteCluster
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private K8sClient k8sClient
    private AirGappedUtils airGappedUtils

    PrometheusStack(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.username = this.config.application["username"]
        this.password = this.config.application["password"]
        this.remoteCluster = this.config.application["remote"]
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
    }

    @Override
    boolean isEnabled() {
        return config.features['monitoring']['active']
    }

    @Override
    void enable() {
        // Note that some specific configuration steps are implemented in ArgoCD
        def namePrefix = config.application['namePrefix']

        def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [
                namePrefix: namePrefix,
                monitoring: [
                        grafanaEmailFrom: config.features['monitoring']['grafanaEmailFrom'] as String,
                        grafanaEmailTo: config.features['monitoring']['grafanaEmailTo'] as String,
                        grafana: [
                                // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                                host: config.features['monitoring']['grafanaUrl'] ? new URL(config.features['monitoring']['grafanaUrl'] as String).host : ""
                        ]
                ],
                mail: [
                        active: config.features['mail']['active'],
                        smtpAddress : config.features['mail']['smtpAddress'],
                        smtpPort : config.features['mail']['smtpPort'],
                        smtpUser : config.features['mail']['smtpUser'],
                        smtpPassword : config.features['mail']['smtpPassword']
                ],
                scmm: getScmmConfiguration(),
                jenkins: getJenkinsConfiguration()
        ]).toPath()
        Map helmValuesYaml = fileSystemUtils.readYaml(tmpHelmValues)

        if (remoteCluster) {
            log.debug("Setting grafana service.type to LoadBalancer since it is running in a remote cluster")
            helmValuesYaml['grafana']['service']['type'] = 'LoadBalancer'
        }

        if (username != null && username != "admin") {
            log.debug("Setting grafana username")
            helmValuesYaml['grafana']['adminUser'] = username
        }
        if (password != null && password != "admin") {
            log.debug("Setting grafana password")
            helmValuesYaml['grafana']['adminPassword'] = password
        }

        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
        k8sClient.createSecret(
                'generic',
                'prometheus-metrics-creds-scmm',
                'monitoring',
                new Tuple2('password', password)
        )

        k8sClient.createSecret(
                'generic',
                'prometheus-metrics-creds-jenkins',
                'monitoring',
                new Tuple2('password', config.jenkins['metricsPassword']),
        )

        if (config.features['mail']['smtpUser'] || config.features['mail']['smtpPassword']) {
            k8sClient.createSecret(
                    'generic',
                    'grafana-email-secret',
                    'monitoring',
                    new Tuple2('user', config.features['mail']['smtpUser']),
                    new Tuple2('password', config.features['mail']['smtpPassword'])
            )
        }

        def helmConfig = config['features']['monitoring']['helm']
        setCustomImages(helmConfig, helmValuesYaml)


        if (config.application['mirrorRepos']) {
            log.debug("Mirroring repos: Deploying prometheus from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config['features']['monitoring']['helm'] as Map)

            String prometheusVersion =
                    new YamlSlurper().parse(Path.of("${config.application['localHelmChartFolder']}/${helmConfig['chart']}",
                    'Chart.yaml'))['version']
            
            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'prometheusstack',
                    '.',
                    prometheusVersion,
                    'monitoring',
                    'kube-prometheus-stack',
                    tmpHelmValues, RepoType.GIT)
        } else {
            fileSystemUtils.writeYaml(helmValuesYaml, tmpHelmValues.toFile())
    
            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'prometheusstack',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    'monitoring',
                    'kube-prometheus-stack',
                    tmpHelmValues)
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
        if (config.scmm['internal']) {
            new URI('http://scmm-scm-manager.default.svc.cluster.local/scm')
        } else {
            new URI("${config.scmm['url']}/scm")
        }
    }

    private Map getJenkinsConfiguration() {
        String path = 'prometheus'
        URI uri
        if (config.jenkins['internal']) {
            uri = new URI("http://jenkins.default.svc.cluster.local/${path}")
        } else {
            uri = new URI("${config.jenkins['url']}/${path}")
        }

        return [
                metricsUsername: config.jenkins['metricsUsername'],
                protocol       : uri.scheme,
                host           : uri.authority,
                path           : uri.path
        ]
    }

    private void setCustomImages(helmConfig, Map helmValuesYaml) {
        setGrafanaImage(helmConfig, helmValuesYaml)
        setGrafanaSidecarImage(helmConfig, helmValuesYaml)
        setPrometheusImage(helmConfig, helmValuesYaml)
        setPrometheusOperatorImage(helmConfig, helmValuesYaml)
        setPrometheusConfigReloaderImage(helmConfig, helmValuesYaml)
    }

    private void setPrometheusConfigReloaderImage(helmConfig, Map helmValuesYaml) {
        String prometheusConfigReloaderImage = helmConfig['prometheusConfigReloaderImage']
        if (prometheusConfigReloaderImage) {
            log.debug("Setting custom prometheus-config-reloader image as requested for prometheus-stack")
            def image = DockerImageParser.parse(prometheusConfigReloaderImage)
            MapUtils.deepMerge([
                    prometheusOperator: [
                            prometheusConfigReloader: [
                                    image: [
                                            registry  : image.registry,
                                            repository: image.repository,
                                            tag       : image.tag
                                    ]
                            ]
                    ]
            ], helmValuesYaml)
        }
    }

    private void setPrometheusOperatorImage(helmConfig, Map helmValuesYaml) {
        String prometheusOperatorImage = helmConfig['prometheusOperatorImage']
        if (prometheusOperatorImage) {
            log.debug("Setting custom prometheus-operator image as requested for prometheus-stack")
            def image = DockerImageParser.parse(prometheusOperatorImage)
            MapUtils.deepMerge([
                    prometheusOperator: [
                            image: [
                                    registry: image.registry,
                                    repository: image.repository,
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }
    }

    private void setPrometheusImage(helmConfig, Map helmValuesYaml) {
        String prometheusImage = helmConfig['prometheusImage']
        if (prometheusImage) {
            log.debug("Setting custom prometheus-operator image as requested for prometheus-stack")
            def image = DockerImageParser.parse(prometheusImage)
            MapUtils.deepMerge([
                    prometheus: [
                            prometheusSpec: [
                                    image: [
                                            registry: image.registry,
                                            repository: image.repository,
                                            tag       : image.tag
                                    ]
                            ]
                    ]
            ], helmValuesYaml)
        }
    }

    private void setGrafanaSidecarImage(helmConfig, Map helmValuesYaml) {
        String grafanaSidecarImage = helmConfig['grafanaSidecarImage']
        if (grafanaSidecarImage) {
            log.debug("Setting custom grafana-sidecar image as requested for prometheus-stack")
            def image = DockerImageParser.parse(grafanaSidecarImage)
            MapUtils.deepMerge([
                    grafana: [
                            sidecar: [
                                    image: [
                                            repository: image.getRegistryAndRepositoryAsString(),
                                            tag       : image.tag
                                    ]
                            ]
                    ]
            ], helmValuesYaml)
        }
    }

    private void setGrafanaImage(helmConfig, Map helmValuesYaml) {
        String grafanaImage = helmConfig['grafanaImage']
        if (grafanaImage) {
            log.debug("Setting custom grafana image as requested for prometheus-stack")
            def image = DockerImageParser.parse(grafanaImage)
            MapUtils.deepMerge([
                    grafana: [
                            image: [
                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
                            ]
                    ]
            ], helmValuesYaml)
        }
    }
}
