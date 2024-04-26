package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.HelmClient
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
class HelmStrategy implements DeploymentStrategy {
    private HelmClient helmClient
    private Map config

    HelmStrategy(Configuration config, HelmClient helmClient) {
        this.config = config.getConfig()
        this.helmClient = helmClient
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {
        def namePrefix = config.application['namePrefix']

        helmClient.addRepo(repoName, repoURL)
        helmClient.upgrade(releaseName, "$repoName/$chart",
                [namespace: "${namePrefix}${namespace}".toString(),
                 version  : version,
                 values   : helmValuesPath.toString()])
    }
}
