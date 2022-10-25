package com.cloudogu.gitops.argocd

import com.cloudogu.gitops.utils.GitClient
import com.cloudogu.gitops.Feature
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCD extends Feature {

    private Map config
    private GitClient git

    ArgoCD(Map config, GitClient gitClient = new GitClient(config)) {
        this.config = config
        this.git = gitClient
    }

    @Override
    boolean isEnabled() {
        config.features["argocd"]["active"]
    }

    @Override
    void enable() {
        String absoluteControlAppTmpDir = "/tmp/repo_tmp_dir_for_control_app"
        String localSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        // todo refactor this to be better testable
        ArgoCDNotifications notifications = new ArgoCDNotifications(config, absoluteControlAppTmpDir)
        Mailhog mailhog = new Mailhog(config, absoluteControlAppTmpDir)
        PrometheusStack prometheus = new PrometheusStack(config, absoluteControlAppTmpDir)

        git.clone(localSrcDir, scmmRepoTarget, absoluteControlAppTmpDir)
        notifications.install()
        mailhog.install()
        prometheus.install()
        git.commitAndPush(scmmRepoTarget, absoluteControlAppTmpDir)
    }
}
