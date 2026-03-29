package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.kubernetes.api.HelmClient
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
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

        {

            log.debug("Imperatively deploying helm release ${releaseName} basing on chart ${chartOrPath} from ${repoURL}, " +
                    "version ${version}, into namespace ${namespace}. Using values:\n${helmValuesPath.toFile().text}")

            if (RepoType.HELM  == repoType ) {
                 log.debug("helm chart by http")
                helmClient.addRepo(repoName, repoURL)
            helmClient.upgrade(releaseName, "$repoName/$chartOrPath",
                    [namespace: namespace,
                     version  : version,
                     values   : helmValuesPath.toString()])
             } else if (RepoType.OCI  == repoType){
                def registry = extractOciRegistry(repoURL)
                helmClient.upgrade(chartOrPath,"$repoURL",
                    [namespace: namespace,
                     version  : version,
                     values   : helmValuesPath.toString()])
            }
        }

    }
    private static String extractOciRegistry(String repoURL) {
    // oci://ghcr.io/myorg/charts  ->  ghcr.io
    return repoURL.replace('oci://', '').split('/')[0]
}
}