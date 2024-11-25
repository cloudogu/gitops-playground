package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.HelmClient
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
class HelmStrategy implements DeploymentStrategy {
    private HelmClient helmClient
    private Config config

    HelmStrategy(Config config, HelmClient helmClient) {
        this.config = config
        this.helmClient = helmClient
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
                       String releaseName, Path helmValuesPath, RepoType repoType) {

        if (repoType == RepoType.GIT) {
            // This would be possible with plugins or by pulling the repo first, but for now, we don't need it
            throw new RuntimeException("Unable to deploy helm chart via Helm CLI from Git URL, because helm does not support this out of the box.\n" +
                    "Repo URL: ${repoURL}")
        }

        def namePrefix = config.application.namePrefix

        helmClient.addRepo(repoName, repoURL)
        helmClient.upgrade(releaseName, "$repoName/$chartOrPath",
                [namespace: "${namePrefix}${namespace}".toString(),
                 version  : version,
                 values   : helmValuesPath.toString()])
    }
}
