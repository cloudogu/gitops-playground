package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Config

import io.micronaut.context.annotation.Primary

import java.nio.file.Path
import jakarta.inject.Singleton

@Singleton
@Primary
class Deployer implements DeploymentStrategy {
	Config config
	ArgoCdApplicationStrategy argoCdStrategy
	HelmStrategy helmStrategy

	Deployer(Config config, ArgoCdApplicationStrategy argoCdStrategy, HelmStrategy helmStrategy) {
		this.helmStrategy = helmStrategy
		this.argoCdStrategy = argoCdStrategy
		this.config = config
	}

	@Override
	void deployFeature(
			String repoURL, String repoName, String chartOrPath, String version, String namespace,
			String releaseName, Path helmValuesPath, RepoType repoType, boolean initByHelm = false) {

		if (initByHelm) {
			helmStrategy.deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType)
		}

		if (config.features['argocd']['active']) {
			argoCdStrategy.deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType)
		}
	}

}