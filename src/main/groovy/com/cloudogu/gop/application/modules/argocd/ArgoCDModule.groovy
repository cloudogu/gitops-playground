package com.cloudogu.gop.application.modules.argocd

import com.cloudogu.gop.application.clients.git.GitClient
import com.cloudogu.gop.application.modules.GopModule
import com.cloudogu.gop.application.modules.argocd.config.MetricsConfig
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCDModule implements GopModule {

    private Map config
    private GitClient git

    ArgoCDModule(Map config, GitClient gitClient) {
        this.config = config
        this.git = gitClient
    }

    @Override
    void run() {
        if(config.modules["argocd"]["active"]) {
            log.info("Running argocd module")

            configuringArgocdControlApp()
        }
    }

    private void configuringArgocdControlApp() {
        String absoluteControlAppTmpDir = "/tmp/repo_tmp_dir_for_control_app"
        // todo refactor this to be better testable
        MetricsConfig metricsConfig = new MetricsConfig(config, absoluteControlAppTmpDir)
        String localGopSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        git.clone(localGopSrcDir, scmmRepoTarget, absoluteControlAppTmpDir)
        metricsConfig.metricsConfigurationInRepo()
        git.commitAndPush(scmmRepoTarget, absoluteControlAppTmpDir)
    }
}
