package com.cloudogu.gitops.features.deployment

import java.nio.file.Path

interface DeploymentStrategy {
    void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
                       String releaseName, Path helmValuesPath, RepoType repoType)

    default void deployFeature(String repoURL, String repoName, String chart, String version, String namespace,
                               String releaseName, Path helmValuesPath) {
        deployFeature(repoURL, repoName, chart, version, namespace, releaseName, helmValuesPath, RepoType.HELM)
    }
    
    enum RepoType { HELM, GIT }
}
