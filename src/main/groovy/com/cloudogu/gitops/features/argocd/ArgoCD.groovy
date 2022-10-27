package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.GitClient
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCD extends Feature {

    private Map config
    private GitClient git

    private File controlAppTmpDir

    ArgoCD(Map config, GitClient gitClient = new GitClient(config)) {
        this.config = config
        this.git = gitClient

        controlAppTmpDir = File.createTempDir("gitops-playground-control-app")
        controlAppTmpDir.deleteOnExit()
    }

    @Override
    boolean isEnabled() {
        config.features["argocd"]["active"]
    }

    @Override
    void enable() {
        String localSrcDir = "argocd/control-app"
        String scmmRepoTarget = "argocd/control-app"

        git.clone(localSrcDir, scmmRepoTarget, controlAppTmpDir.absolutePath)

        new ArgoCDNotifications(config, controlAppTmpDir.absolutePath).install()

        if (!config.features['secrets']['active']) {
            new File(controlAppTmpDir.absolutePath + '/' +  "applications/secrets").delete()
        }
        if (!config.features['monitoring']['active']) {
            new File(controlAppTmpDir.absolutePath + '/' +  "applications/monitoring").delete()
        }

        git.commitAndPush(scmmRepoTarget, controlAppTmpDir.absolutePath)
    }
}
