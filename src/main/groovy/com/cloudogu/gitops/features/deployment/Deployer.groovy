package com.cloudogu.gitops.features.deployment

import java.nio.file.Path

class Deployer implements DeploymentStrategy {
    private Map config
    private DeploymentStrategy argoCdStrategy
    private DeploymentStrategy helmStrategy

    protected Deployer(Map config, DeploymentStrategy argoCdStrategy, DeploymentStrategy helmStrategy) {
        this.helmStrategy = helmStrategy
        this.argoCdStrategy = argoCdStrategy
        this.config = config
    }

//    Deployer(Map config) {
//        this(config, new ArgoCdApplicationStrategy(), new HelmStrategy())
//    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {
        if (config.features['argocd']['active'] && !config.features['fluxv2']['active']) {
            argoCdStrategy.deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath)
        } else {
            helmStrategy.deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath)
        }
    }
}
