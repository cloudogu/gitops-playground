package com.cloudogu.gitops.core.modules.metrics.argocd


import groovy.util.logging.Slf4j

@Slf4j
class PrometheusStack {

    private boolean deployMetrics
    private String username
    private String password
    private String tmpGitRepoDir
    private com.cloudogu.gitops.core.utils.FileSystemUtils fileSystemUtils
    private com.cloudogu.gitops.core.clients.k8s.K8sClient k8sClient

    PrometheusStack(Map config, String tmpGitRepoDir, com.cloudogu.gitops.core.utils.FileSystemUtils fileSystemUtils = new com.cloudogu.gitops.core.utils.FileSystemUtils(), com.cloudogu.gitops.core.clients.k8s.K8sClient k8sClient = new com.cloudogu.gitops.core.clients.k8s.K8sClient()) {
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
