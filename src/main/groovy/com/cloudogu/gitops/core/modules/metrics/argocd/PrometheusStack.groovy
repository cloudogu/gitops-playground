package com.cloudogu.gitops.core.modules.metrics.argocd

import com.cloudogu.gitops.core.clients.k8s.K8sClient
import com.cloudogu.gitops.core.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class PrometheusStack {

    static final String STACK_YAML_PATH = "applications/application-kube-prometheus-stack-helm.yaml"
    
    private boolean deployMetrics
    private boolean remoteCluster
    private String username
    private String password
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils
    private K8sClient k8sClient

    PrometheusStack(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils(), 
                    K8sClient k8sClient = new K8sClient()) {
        this.username = config.application["username"] 
        this.password = config.application["password"]
        this.deployMetrics = config.modules["metrics"]
        this.remoteCluster = config.application["remote"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
    }

    void configure() {
        if (deployMetrics) {
            
            if (remoteCluster) {
                log.debug("Setting grafana service.type to LoadBalancer since it is running in a remote cluster")
                fileSystemUtils.replaceFileContent(tmpGitRepoDir, STACK_YAML_PATH, "NodePort", "LoadBalancer")
            }
            
            deploy()
        } else {
            disable()
        }
    }

    private void deploy() {
        log.info("Deploying prometheus stack")

        k8sClient.applyYaml(fileSystemUtils.rootDir + "/metrics/grafana/dashboards/")

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

    private void disable() {
        log.info("Disabling prometheus stack")
        String prometheusStack = tmpGitRepoDir + "/applications/application-kube-prometheus-stack-helm.yaml"
        new File(prometheusStack).delete()
    }
}
