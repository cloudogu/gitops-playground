package com.cloudogu.gitops.features.deployment

import java.nio.file.Path

interface DeploymentStrategy {
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath)
}
