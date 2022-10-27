package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
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
    private K8sClient k8sClient
    HelmClient helmClient

    PrometheusStack(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(),
                    K8sClient k8sClient = new K8sClient(), HelmClient helmClient = new HelmClient()) {
        this.config = config
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.remoteCluster = config.application["remote"]
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.helmClient = helmClient
    }

    @Override
    boolean isEnabled() {
        return config.features['monitoring']['active']
    }

    @Override
    void enable() {
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
        helmClient.addRepo(getClass().simpleName, helmConfig['repoURL'] as String)
        helmClient.upgrade('kube-prometheus-stack', "${getClass().simpleName}/${helmConfig['chart']}",
                helmConfig['version'] as String,
                [namespace: 'monitoring',
                 values: "${tmpHelmValues.toString()}"])
    }
}
