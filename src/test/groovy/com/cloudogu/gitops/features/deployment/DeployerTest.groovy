package com.cloudogu.gitops.infrastructure.deployment

import jakarta.inject.Provider
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class DeployerTest {
    private Provider<ArgoCdApplicationStrategy> argoCdStratProvider = mock(Provider)
    private ArgoCdApplicationStrategy argoCdStrat = mock(ArgoCdApplicationStrategy)
    private HelmStrategy helmStrat = mock(HelmStrategy.class)

    @Test
    void 'When init via Helm is active, deploys imperatively via helm and via Argo CD'() {
        def deployer = createDeployer()

        deployer.deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM, true)


		verify(helmStrat).deployFeature("repoURL", "repoName", "chart", "version", "namespace",
		                                "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM)
        verify(argoCdStrat, never()).deployFeature(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(Path))

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
        when(argoCdStratProvider.get()).thenReturn(argoCdStrat)
        return new Deployer(argoCdStratProvider, helmStrat)
    }
}