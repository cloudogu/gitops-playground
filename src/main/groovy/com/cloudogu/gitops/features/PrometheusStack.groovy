package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.features.deployment.Deployer
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.DockerImageParser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j

@Slf4j
class PrometheusStack extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/monitoring/prometheus-stack-helm-values.ftl.yaml"
    
    private Map config
    private boolean remoteCluster
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer

    PrometheusStack(
            Map config,
            FileSystemUtils fileSystemUtils = new FileSystemUtils(),
            DeploymentStrategy deployer = new Deployer(config)
    ) {
        this.deployer = deployer
        this.config = config
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.remoteCluster = config.application["remote"]
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return config.features['monitoring']['active']
    }

    @Override
    void enable() {
        // Note that some specific configuration steps are implemented in ArgoCD
        def namePrefix = config.application['namePrefix']
        def tmpHelmValues = new TemplatingEngine().replaceTemplate(fileSystemUtils.copyToTempDir(HELM_VALUES_PATH).toFile(), [namePrefix: namePrefix]).toPath()
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
                            prometheusConfigReloaderImage: [
                                    repository: image.getRegistryAndRepositoryAsString(),
                                    tag       : image.tag
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
                                    repository: image.getRegistryAndRepositoryAsString(),
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
                                            repository: image.getRegistryAndRepositoryAsString(),
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
