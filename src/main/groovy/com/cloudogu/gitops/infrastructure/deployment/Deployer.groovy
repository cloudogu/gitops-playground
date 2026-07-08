package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace

import java.nio.file.Path
import jakarta.inject.Provider
import jakarta.inject.Singleton
import groovy.transform.CompileStatic

@CompileStatic
@Singleton
class Deployer {

	final Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider
	final HelmStrategy helmStrategy

	Deployer(Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider,
		HelmStrategy helmStrategy) {
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
		DeploymentStrategy.RepoType repoType,
		boolean bootstrapWithHelm = false,
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
				repositoryWorkspace)
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