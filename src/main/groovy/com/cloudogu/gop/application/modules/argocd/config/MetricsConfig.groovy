package com.cloudogu.gop.application.modules.argocd.config

import com.cloudogu.gop.application.clients.k8s.K8sClient
import com.cloudogu.gop.application.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import org.springframework.security.crypto.bcrypt.BCrypt

@Slf4j
class MetricsConfig {

    private boolean remoteCluster
    private String argocdUrl
    private boolean deployMetrics
    private String username
    private String password
    private String mailhogUsername
    private String mailhogPassword
    private String tmpGitRepoDir
    private FileSystemUtils fileSystemUtils
    private K8sClient k8sClient

    MetricsConfig(Map config, String tmpGitRepoDir, FileSystemUtils fileSystemUtils = new FileSystemUtils(), K8sClient k8sClient = new K8sClient()) {
        this.remoteCluster = config.application["remote"]
        this.username = config.application["username"]
        this.password = config.application["password"]
        this.argocdUrl = config.modules["argocd"]["url"]
        this.deployMetrics = config.modules["metrics"]
        this.mailhogUsername = config.mailhog["username"]
        this.mailhogPassword = config.mailhog["password"]
        this.tmpGitRepoDir = tmpGitRepoDir
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
    }

    void metricsConfigurationInRepo() {
        log.info("Configuring metrics for argocd")
        configureArgocdNotifications()
        configureMailhog()
        configureMetrics()
    }

    private void configureArgocdNotifications() {
        String argoNotificationsYaml = "applications/application-argocd-notifications.yaml"

        if (argocdUrl != null && argocdUrl != "") {
            log.debug("Setting argocd url")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, argoNotificationsYaml, "argocdUrl: http://localhost:9092", "argocdUrl: $argocdUrl")
        }
    }

    private void configureMailhog() {
        log.debug("Configuring mailhog")
        String mailhogYaml = "applications/application-mailhog-helm.yaml"

        if (!remoteCluster) {
            log.debug("Setting mailhog service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, mailhogYaml, "LoadBalancer", "NodePort")
        }

        if (username != mailhogUsername || password != mailhogPassword) {
            log.debug("Setting new mailhog credentials")
            String bcryptMailhogPassword = BCrypt.hashpw(mailhogPassword, BCrypt.gensalt(4))
            String from = "fileContents: \"admin:\$2a\$04\$bM4G0jXB7m7mSv4UT8IuIe3.Bj6i6e2A13ryA0ln.hpyX7NeGQyG.\""
            String to = "fileContents: \"$mailhogUsername:$bcryptMailhogPassword\""
            fileSystemUtils.replaceFileContent(tmpGitRepoDir, mailhogYaml, from, to)
        } else {
            log.debug("Not setting mailhog credentials since none were set. Using default application credentials")
        }
    }

    private void configureMetrics() {
        if (deployMetrics) {
            deployPrometheusStack()
        } else {
            disablePrometheusStack()
        }
    }

    private void deployPrometheusStack() {
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

    private void disablePrometheusStack() {
        log.info("Disabling prometheus stack")
        String prometheusStack = tmpGitRepoDir + "/applications/application-kube-prometheus-stack-helm.yaml"
        new File(prometheusStack).delete()
    }
}
