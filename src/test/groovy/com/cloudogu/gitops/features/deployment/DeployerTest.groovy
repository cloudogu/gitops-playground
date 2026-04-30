package com.cloudogu.gitops.features.deployment

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

import java.nio.file.Path

import org.junit.jupiter.api.Test

class DeployerTest {
	private ArgoCdApplicationStrategy argoCdStrat = mock(ArgoCdApplicationStrategy.class)
	private HelmStrategy helmStrat = mock(HelmStrategy.class)

	@Test
	void 'When init via Helm is active, deploys imperatively via helm'() {
		def deployer = createDeployer()

		deployer.deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM, true)

		verify(argoCdStrat, never()).deployFeature(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(Path))
		verify(helmStrat).deployFeature("repoURL", "repoName", "chart", "version", "namespace",
		                                "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM)
	}

	@Test
	void 'When Argo CD enabled, deploys natively via Argo CD'() {
		def deployer = createDeployer()

		deployer.deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM)

		verify(argoCdStrat).deployFeature("repoURL", "repoName", "chart", "version", "namespace",
		                                  "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM)
		verify(helmStrat, never()).deployFeature(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(Path))
	}

	private Deployer createDeployer() {
		return new Deployer(argoCdStrat, helmStrat)
	}
}