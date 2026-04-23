package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType

import java.nio.file.Path

import org.jvnet.hk2.annotations.Service

@Service
class Deployer {

	@Lazy
	ArgoCdApplicationStrategy argoCdStrategy

	HelmStrategy helmStrategy

	Deployer(HelmStrategy helmStrategy) {
		this.helmStrategy = helmStrategy
	}

	void deployFeature(
			String repoURL, String repoName, String chartOrPath, String version, String namespace,
			String releaseName, Path helmValuesPath, RepoType repoType, boolean initByHelm = false) {

		if (initByHelm) {
			helmStrategy.deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType)
		}
		argoCdStrategy.deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType)
	}
}