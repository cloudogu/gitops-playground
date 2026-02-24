package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Config

import io.micronaut.context.annotation.Primary

import java.nio.file.Path
import jakarta.inject.Singleton

@Singleton
@Primary
class Deployer implements DeploymentStrategy {
    private Config config
    private ArgoCdApplicationStrategy argoCdStrategy
    private HelmStrategy helmStrategy

    Deployer(Config config, ArgoCdApplicationStrategy argoCdStrategy, HelmStrategy helmStrategy) {
        this.helmStrategy = helmStrategy
        this.argoCdStrategy = argoCdStrategy
        this.config = config
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