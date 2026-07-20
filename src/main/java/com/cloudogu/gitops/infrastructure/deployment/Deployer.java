package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import lombok.RequiredArgsConstructor;

import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.nio.file.Path;

@Singleton
@RequiredArgsConstructor
public class Deployer {

    private final Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider;
    private final HelmStrategy helmStrategy;

    public HelmStrategy getHelmStrategy() {
        return helmStrategy;
    }

    public void deployFeature(String repoURL,
                              String repoName,
                              String chartOrPath,
                              String version,
                              String namespace,
                              String releaseName,
                              Path helmValuesPath,
                              DeploymentStrategy.RepoType repoType,
                              boolean bootstrapWithHelm,
                              DeploymentContext context,
                              RepositoryWorkspace repositoryWorkspace) {

        if (bootstrapWithHelm) {
            helmStrategy.deployFeature(repoURL,
                    repoName,
                    chartOrPath,
                    version,
                    namespace,
                    releaseName,
                    helmValuesPath,
                    repoType,
                    context,
                    repositoryWorkspace);
        }

        argoCdStrategyProvider.get().deployFeature(repoURL,
                repoName,
                chartOrPath,
                version,
                namespace,
                releaseName,
                helmValuesPath,
                repoType,
                context,
                repositoryWorkspace);
    }
    
    public void deployFeature(String repoURL,
                              String repoName,
                              String chartOrPath,
                              String version,
                              String namespace,
                              String releaseName,
                              Path helmValuesPath,
                              DeploymentStrategy.RepoType repoType,
                              DeploymentContext context,
                              RepositoryWorkspace repositoryWorkspace) {
        deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType, false, context, repositoryWorkspace);
    }
}
