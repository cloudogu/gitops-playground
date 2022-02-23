package com.cloudogu.gop.application.modules.metrics

import com.cloudogu.gop.application.modules.GopModule
import com.cloudogu.gop.tools.git.Git
import com.cloudogu.gop.tools.k8s.K8sClient
import com.cloudogu.gop.utils.FileSystemUtils

class MetricsModule implements GopModule {

    private boolean remoteCluster
    private String argoCdUrl
    private boolean deployMetrics
    private String username
    private String password
    private Map scmmConfig

    MetricsModule(Map app, String argoUrl, boolean metrics, Map scmmConfig) {
        this.remoteCluster = app.remote
        this.username = app.username
        this.password = app.password
        this.argoCdUrl = argoUrl
        this.deployMetrics = metrics
        this.scmmConfig = scmmConfig
    }

    @Override
    void run() {
        Git git = new Git(scmmConfig)

        git.initRepoWithSource("argocd/control-app", "argocd/control-app", { tmpGitRepoDir ->

            String mailhogYaml = "applications/application-mailhog-helm.yaml"
            String argoNotificationsYaml = "applications/application-argocd-notifications.yaml"

            if (!remoteCluster) {
                FileSystemUtils.replaceFileContent(tmpGitRepoDir as String, mailhogYaml, "LoadBalancer", "NodePort")
            }

            if (argoCdUrl != null && argoCdUrl != "") {
                FileSystemUtils.replaceFileContent(tmpGitRepoDir as String, argoNotificationsYaml, "argocdUrl: http://localhost:9092", "argocdUrl: $argoCdUrl")
            }

            if (deployMetrics) {
                deployPrometheusStack(tmpGitRepoDir as String)
            } else {
                disablePrometheusStack(tmpGitRepoDir as String)
            }
        })
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
