package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient

import java.nio.file.Path

class Deployer implements DeploymentStrategy {
    private Map config
    private ArgoCdApplicationStrategy argoCdStrategy
    private HelmStrategy helmStrategy

    protected Deployer(Map config, ArgoCdApplicationStrategy argoCdStrategy, HelmStrategy helmStrategy) {
        this.helmStrategy = helmStrategy
        this.argoCdStrategy = argoCdStrategy
        this.config = config
    }

    Deployer(Map config) {
        this(config, new ArgoCdApplicationStrategy(config, new FileSystemUtils()), new HelmStrategy(config, new HelmClient()))
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {
        // For now, we support "native" installation for Argo CD only.
        if (config.features['argocd']['active'] && !config.features['fluxv2']) {
            argoCdStrategy.deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath)
        } else {
            // Installing via Flux is not implemented, yet. See #114.
            // When Argo CD and Flux are installed, we fall back to using imperative helm installation as kind of 
            // neutral ground, in order to not confuse the Flux user too much.
            helmStrategy.deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath)
        }
    }
}
