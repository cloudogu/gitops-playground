package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.helm.HelmClient
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

import java.nio.file.Files
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
    void deployFeature(String repoURL,
                       String repoName,
                       String chartOrPath,
                       String version,
                       String namespace,
                       String releaseName,
                       Path helmValuesPath,
                       RepoType repoType) {

        if (repoType == RepoType.GIT) {
            throw new RuntimeException(
                    "Unable to deploy helm chart via Helm CLI from Git URL, because helm does not support this out of the box.\n" +
                            "Repo URL: ${repoURL}"
            )
        }

        boolean localChart = isLocalChartPath(chartOrPath)

        String chartReference

        if (localChart) {
            chartReference = chartOrPath
            log.debug("Imperatively deploying helm release ${releaseName} from local chart path ${chartReference}, " +
                    "version ${version}, into namespace ${namespace}. Using values:\n${helmValuesPath.toFile().text}")
        } else {
            helmClient.addRepo(repoName, repoURL)
            chartReference = "$repoName/$chartOrPath"

            log.debug("Imperatively deploying helm release ${releaseName} basing on chart ${chartOrPath} from ${repoURL}, " +
                    "version ${version}, into namespace ${namespace}. Using values:\n${helmValuesPath.toFile().text}")
        }

        helmClient.upgrade(releaseName, chartReference,
                [namespace: namespace,
                 version  : version,
                 values   : helmValuesPath.toString()])
    }

    private static boolean isLocalChartPath(String chartOrPath) {
        if (!chartOrPath) {
            return false
        }

        Path path = Path.of(chartOrPath)

        return path.isAbsolute()
                || chartOrPath.startsWith("./")
                || chartOrPath.startsWith("../")
                || Files.exists(path)
    }
}