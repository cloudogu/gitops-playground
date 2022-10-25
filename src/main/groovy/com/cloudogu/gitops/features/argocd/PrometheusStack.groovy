package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class PrometheusStack extends Feature {

    static final String STACK_YAML_PATH = "applications/system/application-kube-prometheus-stack-helm.yaml"
    
    private Map config
    private boolean remoteCluster
    private String username
    private String password
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils
    private K8sClient k8sClient

    PrometheusStack(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils(),
                    K8sClient k8sClient = new K8sClient()) {
        this.config = config
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.remoteCluster = config.application["remote"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
    }

    @Override
    boolean isEnabled() {
        return config.features["metrics"] && config.features["argocd"]["active"]
    }

    @Override
    void enable() {
        if (remoteCluster) {
            log.debug("Setting grafana service.type to LoadBalancer since it is running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, STACK_YAML_PATH, "NodePort", "LoadBalancer")
        }

        k8sClient.applyYaml(fileSystemUtils.rootDir + "/system/metrics/grafana/dashboards/")

        if (username != null && username != "admin") {
            log.debug("Setting grafana username")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, STACK_YAML_PATH,
                    'adminUser: admin', "adminUser: $username")
        }
        if (password != null && password != "admin") {
            log.debug("Setting grafana password")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, STACK_YAML_PATH,
                    "adminPassword: admin", "adminPassword: $password")
        }
    }

    @Override
    void disable() {
        log.info("Disabling prometheus stack")
        String prometheusStack = tmpGitRepoDir + '/' + STACK_YAML_PATH
        new File(prometheusStack).delete()
    }
}
