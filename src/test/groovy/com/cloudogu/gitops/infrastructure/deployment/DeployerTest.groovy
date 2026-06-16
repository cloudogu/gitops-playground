package com.cloudogu.gitops.infrastructure.deployment

import static org.mockito.Mockito.*

import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType

import java.nio.file.Path
import jakarta.inject.Provider

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.InOrder

class DeployerTest {

	private Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider
	private ArgoCdApplicationStrategy argoCdStrategy
	private HelmStrategy helmStrategy
	private Path helmValuesPath
	private Deployer deployer

	private static final String REPO_URL = "https://example.com/repo.git"
	private static final String REPO_NAME = "repo-name"
	private static final String CHART_OR_PATH = "chart-or-path"
	private static final String VERSION = "1.2.3"
	private static final String NAMESPACE = "namespace"
	private static final String RELEASE_NAME = "release-name"
	private static final RepoType REPO_TYPE = RepoType.HELM

	@BeforeEach
	void setup() {
		argoCdStrategyProvider = mock(Provider)
		argoCdStrategy = mock(ArgoCdApplicationStrategy)
		helmStrategy = mock(HelmStrategy)
		helmValuesPath = mock(Path)

		deployer = new Deployer(argoCdStrategyProvider, helmStrategy)
	}

	@Test
	void "deploys via ArgoCD when ArgoCD is enabled and init by Helm is disabled"() {
		when(argoCdStrategyProvider.get()).thenReturn(argoCdStrategy)

		deployFeature(false)

		verify(argoCdStrategyProvider).get()

		verify(argoCdStrategy).deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE
		)

		verifyNoInteractions(helmStrategy)
		verifyNoMoreInteractions(argoCdStrategyProvider, argoCdStrategy)
	}

	@Test
	void "deploys via Helm before ArgoCD when ArgoCD is enabled and init by Helm is enabled"() {
		when(argoCdStrategyProvider.get()).thenReturn(argoCdStrategy)

		deployFeature(true)

		InOrder inOrder = inOrder(helmStrategy, argoCdStrategyProvider, argoCdStrategy)

		inOrder.verify(helmStrategy).deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE
		)

		inOrder.verify(argoCdStrategyProvider).get()

		inOrder.verify(argoCdStrategy).deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE
		)

		verifyNoMoreInteractions(helmStrategy, argoCdStrategyProvider, argoCdStrategy)
	}


	private void deployFeature(boolean initByHelm) {
		deployer.deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE,
			initByHelm
		)
	}
}