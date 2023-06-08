package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.HelmClient

import java.nio.file.Path

class HelmStrategy implements DeploymentStrategy {
    private HelmClient helmClient

    HelmStrategy(HelmClient helmClient) {
        this.helmClient = helmClient
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {
        helmClient.addRepo(repoName, repoURL)
        helmClient.upgrade(releaseName, "$repoName/$chart",
                [namespace: namespace,
                 version  : version,
                 values   : helmValuesPath.toString()])
    }
}
