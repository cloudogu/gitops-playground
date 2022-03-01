package com.cloudogu.gop.application.modules.metrics

import com.cloudogu.gop.application.clients.git.GitClient
import com.cloudogu.gop.application.clients.k8s.K8sClient
import com.cloudogu.gop.application.modules.GopModule
import com.cloudogu.gop.application.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class MetricsModule implements GopModule {

    private boolean remoteCluster
    private String argocdUrl
    private boolean deployMetrics
    private String username
    private String password
    private Map scmmConfig

    MetricsModule(Map appConfig, String argocdUrl, boolean metrics, Map scmmConfig) {
        this.remoteCluster = appConfig.application["remote"]
        this.username = appConfig.application["username"]
        this.password = appConfig.application["password"]
        this.argocdUrl = argocdUrl
        this.deployMetrics = metrics
        this.scmmConfig = scmmConfig
    }

    @Override
    void run() {
        log.info("Running metrics module")
        GitClient git = new GitClient(scmmConfig)

        String localGopSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        String absoluteTmpDirLocation = "/tmp/repo_tmp_dir_for_metrics"

        log.debug("Cloning argocd control-app repo")
        git.clone(localGopSrcDir, scmmRepoTarget, absoluteTmpDirLocation)
        log.debug("Configuring metrics specific values inside repo")
        metricsConfigurationInRepo(absoluteTmpDirLocation)
        log.debug("Pushing configured argocd control-app repo")
        git.commitAndPush(scmmRepoTarget, absoluteTmpDirLocation)
    }

    private void metricsConfigurationInRepo(String tmpGitRepoDir) {
        String mailhogYaml = "applications/application-mailhog-helm.yaml"
        String argoNotificationsYaml = "applications/application-argocd-notifications.yaml"

        if (!remoteCluster) {
            FileSystemUtils.replaceFileContent(tmpGitRepoDir, mailhogYaml, "LoadBalancer", "NodePort")
        }

        if (argocdUrl != null && argocdUrl != "") {
            FileSystemUtils.replaceFileContent(tmpGitRepoDir, argoNotificationsYaml, "argocdUrl: http://localhost:9092", "argocdUrl: $argocdUrl")
        }

        if (deployMetrics) {
            log.info("Deploying prometheus stack")
            deployPrometheusStack(tmpGitRepoDir)
        } else {
            disablePrometheusStack(tmpGitRepoDir)
        }
    }

    private void deployPrometheusStack(String tmpGitRepoDir) {
        new K8sClient().applyYaml(FileSystemUtils.getGopRoot() + "/metrics/dashboards/")

        String prometheusStack = "applications/application-kube-prometheus-stack-helm.yaml"

        if (username != null && username != "admin") {
            FileSystemUtils.replaceFileContent(tmpGitRepoDir, prometheusStack, "adminUser: admin", " adminUser:  $username")
        }
        if (password != null && password != "admin") {
            FileSystemUtils.replaceFileContent(tmpGitRepoDir, prometheusStack, "adminPassword: admin", "adminPassword: $password")
        }
    }

    private void disablePrometheusStack(String tmpGitRepoDir) {
        String prometheusStack = tmpGitRepoDir + "/applications/application-kube-prometheus-stack-helm.yaml"
        new File(prometheusStack).delete()
    }
}
