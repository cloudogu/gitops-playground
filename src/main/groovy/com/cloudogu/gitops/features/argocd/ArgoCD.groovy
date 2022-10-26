package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.utils.GitClient
import com.cloudogu.gitops.Feature
import groovy.util.logging.Slf4j

@Slf4j
class ArgoCD extends Feature {

    private Map config
    private GitClient git
    
    private List<Feature> subFeatures = []
    private File controlAppTmpDir

    ArgoCD(Map config, GitClient gitClient = new GitClient(config)) {
        this.config = config
        this.git = gitClient

        controlAppTmpDir = File.createTempDir("gitops-playground-control-app")
        controlAppTmpDir.deleteOnExit()
        subFeatures = createSubFeatures(config)
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
        // .foreach{} syntax leads to  GroovyCastException: Cannot cast object 'com.cloudogu.gitops.features.argocd.ArgoCD$_enable_closure1@441697fd' with class 'com.cloudogu.gitops.features.argocd.ArgoCD$_enable_closure1' to class 'java.util.function.Consumer'
        for (subFeature in subFeatures) {
            subFeature.install()
        }
        if (!config.features["vault"]) {
            new File(controlAppTmpDir.absolutePath + '/' +  "applications/secrets").delete()
        }
        if (!config.features["metrics"]) {
            new File(controlAppTmpDir.absolutePath + '/' +  "applications/monitoring").delete()
        }
        git.commitAndPush(scmmRepoTarget, controlAppTmpDir.absolutePath)
    }

    List createSubFeatures(Map config) {
        subFeatures += new ArgoCDNotifications(config, controlAppTmpDir.absolutePath)
        subFeatures += new Mailhog(config, controlAppTmpDir.absolutePath)
        subFeatures += new PrometheusStack(config, controlAppTmpDir.absolutePath)
    }
}
