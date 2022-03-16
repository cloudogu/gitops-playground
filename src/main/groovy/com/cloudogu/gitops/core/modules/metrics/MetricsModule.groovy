package com.cloudogu.gitops.core.modules.metrics

import com.cloudogu.gitops.core.clients.git.GitClient
import com.cloudogu.gitops.core.modules.Module
import com.cloudogu.gitops.core.modules.metrics.argocd.ArgoCDNotifications
import com.cloudogu.gitops.core.modules.metrics.argocd.Mailhog
import com.cloudogu.gitops.core.modules.metrics.argocd.PrometheusStack
import groovy.util.logging.Slf4j

@Slf4j
class MetricsModule implements Module {

    private Map config
    private GitClient git

    MetricsModule(Map config, GitClient gitClient) {
        this.config = config
        this.git = gitClient
    }

    @Override
    void run() {
        if(config.modules["argocd"]["active"]) {
            log.info("Running metrics module")

            configureArgocdMetrics()
        }
    }

    private void configureArgocdMetrics() {
        String absoluteControlAppTmpDir = "/tmp/repo_tmp_dir_for_control_app"
        String localSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        // todo refactor this to be better testable
        ArgoCDNotifications notifications = new ArgoCDNotifications(config, absoluteControlAppTmpDir)
        Mailhog mailhog = new Mailhog(config, absoluteControlAppTmpDir)
        PrometheusStack prometheus = new PrometheusStack(config, absoluteControlAppTmpDir)

        git.clone(localSrcDir, scmmRepoTarget, absoluteControlAppTmpDir)
        notifications.configure()
        mailhog.configure()
        prometheus.configure()
        git.commitAndPush(scmmRepoTarget, absoluteControlAppTmpDir)
    }
}
