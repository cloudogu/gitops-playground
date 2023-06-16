package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.features.deployment.Deployer
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class PrometheusStack extends Feature {

    static final String HELM_VALUES_PATH = "system/monitoring/prometheus-stack-helm-values.yaml"
    
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
        
        def tmpHelmValues = fileSystemUtils.copyToTempDir(HELM_VALUES_PATH)
        def tmpHelmValuesFolder = tmpHelmValues.parent.toString()
        def tmpHelmValuesFile = tmpHelmValues.fileName.toString()
        
        if (remoteCluster) {
            log.debug("Setting grafana service.type to LoadBalancer since it is running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpHelmValuesFolder, tmpHelmValuesFile, "NodePort", "LoadBalancer")
        }

        if (username != null && username != "admin") {
            log.debug("Setting grafana username")
            fileSystemUtils.replaceFileContent(tmpHelmValuesFolder, tmpHelmValuesFile,
                    'adminUser: admin', "adminUser: $username")
        }
        if (password != null && password != "admin") {
            log.debug("Setting grafana password")
            fileSystemUtils.replaceFileContent(tmpHelmValuesFolder, tmpHelmValuesFile,
                    "adminPassword: admin", "adminPassword: $password")
        }

        def helmConfig = config['features']['monitoring']['helm']
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
}
