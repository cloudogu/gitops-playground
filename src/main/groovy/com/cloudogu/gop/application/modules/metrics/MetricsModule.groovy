package com.cloudogu.gop.application.modules.metrics

import com.cloudogu.gop.application.clients.git.GitClient
import com.cloudogu.gop.application.modules.GopModule
import com.cloudogu.gop.application.modules.metrics.argocdnotifications.ArgoCDNotifications
import com.cloudogu.gop.application.modules.metrics.mailhog.Mailhog
import com.cloudogu.gop.application.modules.metrics.prometheusstack.PrometheusStack
import groovy.util.logging.Slf4j

@Slf4j
class MetricsModule implements GopModule {

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
        String localGopSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        // todo refactor this to be better testable
        ArgoCDNotifications notifications = new ArgoCDNotifications(config, absoluteControlAppTmpDir)
        Mailhog mailhog = new Mailhog(config, absoluteControlAppTmpDir)
        PrometheusStack prometheus = new PrometheusStack(config, absoluteControlAppTmpDir)

        git.clone(localGopSrcDir, scmmRepoTarget, absoluteControlAppTmpDir)
        notifications.configure()
        mailhog.configure()
        prometheus.configure()
        git.commitAndPush(scmmRepoTarget, absoluteControlAppTmpDir)
    }
}
