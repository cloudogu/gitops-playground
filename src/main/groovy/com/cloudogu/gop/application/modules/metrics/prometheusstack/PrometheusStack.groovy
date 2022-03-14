package com.cloudogu.gop.application.modules.metrics.prometheusstack

import com.cloudogu.gop.application.clients.k8s.K8sClient
import com.cloudogu.gop.application.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class PrometheusStack {

    private boolean deployMetrics
    private String username
    private String password
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils
    private K8sClient k8sClient

    PrometheusStack(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils(), K8sClient k8sClient = new K8sClient()) {
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.deployMetrics = config.modules["metrics"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
    }

    void configure() {
        if (deployMetrics) {
            deploy()
        } else {
            disable()
        }
    }

    private void deploy() {
        log.info("Deploying prometheus stack")

        k8sClient.applyYaml(fileSystemUtils.getGopRoot() + "/metrics/grafana/dashboards/")

        String prometheusStack = "applications/application-kube-prometheus-stack-helm.yaml"

        if (username != null && username != "admin") {
            log.debug("Setting grafana username")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, prometheusStack, "adminUser: admin", " adminUser:  $username")
        }
        if (password != null && password != "admin") {
            log.debug("Setting grafana password")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, prometheusStack, "adminPassword: admin", "adminPassword: $password")
        }
    }

    private void disable() {
        log.info("Disabling prometheus stack")
        String prometheusStack = tmpGitRepoDir + "/applications/application-kube-prometheus-stack-helm.yaml"
        new File(prometheusStack).delete()
    }
}
