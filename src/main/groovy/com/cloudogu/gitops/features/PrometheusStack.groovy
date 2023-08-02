package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.DockerImageParser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

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

    PrometheusStack(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.username = this.config.application["username"]
        this.password = this.config.application["password"]
        this.remoteCluster = this.config.application["remote"]
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
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

        def helmConfig = config['features']['monitoring']['helm']
        setCustomImages(helmConfig, helmValuesYaml)

        fileSystemUtils.writeYaml(helmValuesYaml, tmpHelmValues.toFile())

        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                'prometheusstack',
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'monitoring',
                'kube-prometheus-stack',
                tmpHelmValues
        )
    }

    private Map getScmmConfiguration() {
        String protocol = 'http'
        String host = 'scmm-scm-manager.default.svc.cluster.local'
        String path = '/scm/api/v2/metrics/prometheus'
        if (!config.scmm['internal']) {
            URI uri = new URI((config.scmm['url'] as String) + '/api/v2/metrics/prometheus')
            protocol = uri.scheme
            host = uri.authority
            path = uri.path
        }

        return [
                protocol: protocol,
                host    : host,
                path    : path
        ]
    }

    private Map getJenkinsConfiguration() {
        String protocol = 'http'
        String host = 'jenkins.default.svc.cluster.local'
        String path = '/prometheus'
        if (!config.jenkins['internal']) {
            URI uri = new URI((config.jenkins['url'] as String) + path)
            protocol = uri.scheme
            host = uri.authority
            path = uri.path
        }

        return [
                metricsUsername: config.jenkins['metricsUsername'],
                protocol       : protocol,
                host           : host,
                path           : path
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
