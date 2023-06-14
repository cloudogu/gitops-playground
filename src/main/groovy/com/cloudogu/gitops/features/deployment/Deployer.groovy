package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient

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

    Deployer(Map config) {
        this(config, new ArgoCdApplicationStrategy(config, new FileSystemUtils()), new HelmStrategy(new HelmClient()))
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {
        if (config.features['argocd']['active'] && !config.features['fluxv2']) {
            argoCdStrategy.deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath)
        } else {
            helmStrategy.deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath)
        }
    }
}
