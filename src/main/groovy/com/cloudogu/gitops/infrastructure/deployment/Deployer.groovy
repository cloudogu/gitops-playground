package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType

import java.nio.file.Path
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Singleton
class Deployer {

	Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider

	HelmStrategy helmStrategy

	Deployer(Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider, HelmStrategy helmStrategy) {
		this.argoCdStrategyProvider = argoCdStrategyProvider
		this.helmStrategy = helmStrategy
	}

	void deployFeature(String repoURL,
		String repoName,
		String chartOrPath,
		String version,
		String namespace,
		String releaseName,
		Path helmValuesPath,
		RepoType repoType,
		boolean initByHelm = false,
		DeploymentContext context,
		RepositoryWorkspace repositoryWorkspace) {

		if (initByHelm) {
			helmStrategy.deployFeature(repoURL,
				repoName,
				chartOrPath,
				version,
				namespace,
				releaseName,
				helmValuesPath,
				repoType)
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
			repositoryWorkspace)
	}
}