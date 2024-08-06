package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import io.micronaut.context.annotation.Primary
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
@Primary
class Deployer implements DeploymentStrategy {
    private Map config
    private ArgoCdApplicationStrategy argoCdStrategy
    private HelmStrategy helmStrategy

    Deployer(Configuration config, ArgoCdApplicationStrategy argoCdStrategy, HelmStrategy helmStrategy) {
        this.helmStrategy = helmStrategy
        this.argoCdStrategy = argoCdStrategy
        this.config = config.getConfig()
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
                       String releaseName, Path helmValuesPath, RepoType repoType) {
        if (config.features['argocd']['active']) {
            argoCdStrategy.deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType)
        } else {
            helmStrategy.deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType)
        }
    }
}
