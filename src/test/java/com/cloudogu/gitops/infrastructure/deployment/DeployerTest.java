package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Path;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DeployerTest {

	private static final String REPO_URL = "https://example.com/repo.git";
	private static final String REPO_NAME = "repo-name";
	private static final String CHART_OR_PATH = "chart-or-path";
	private static final String VERSION = "1.2.3";
	private static final String NAMESPACE = "namespace";
	private static final String RELEASE_NAME = "release-name";
	private static final RepoType REPO_TYPE = RepoType.HELM;

	private Provider<ArgoCdApplicationStrategy> argoCdStrategyProvider;
	private ArgoCdApplicationStrategy argoCdStrategy;
	private HelmStrategy helmStrategy;
	private Path helmValuesPath;
	private Deployer deployer;
	private DeploymentContext context;
	private RepositoryWorkspace workspace;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() {
		argoCdStrategyProvider = mock(Provider.class);
		argoCdStrategy = mock(ArgoCdApplicationStrategy.class);
		helmStrategy = mock(HelmStrategy.class);
		helmValuesPath = mock(Path.class);
		context = mock(DeploymentContext.class);
		workspace = mock(RepositoryWorkspace.class);

		deployer = new Deployer(argoCdStrategyProvider, helmStrategy);
	}

	@Test
	void deploysViaArgoCdWhenArgoCdIsEnabledAndInitByHelmIsDisabled() {
		when(argoCdStrategyProvider.get()).thenReturn(argoCdStrategy);

		deployFeature(false);

		verify(argoCdStrategyProvider).get();
		verify(argoCdStrategy).deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE,
			context,
			workspace
		);
		verifyNoInteractions(helmStrategy);
		verifyNoMoreInteractions(argoCdStrategyProvider, argoCdStrategy);
	}

	@Test
	void deploysViaHelmBeforeArgoCdWhenArgoCdIsEnabledAndInitByHelmIsEnabled() {
		when(argoCdStrategyProvider.get()).thenReturn(argoCdStrategy);

		deployFeature(true);

		InOrder inOrder = inOrder(helmStrategy, argoCdStrategyProvider, argoCdStrategy);
		inOrder.verify(helmStrategy).deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE,
			context,
			workspace
		);
		inOrder.verify(argoCdStrategyProvider).get();
		inOrder.verify(argoCdStrategy).deployFeature(
			REPO_URL,
			REPO_NAME,
			CHART_OR_PATH,
			VERSION,
			NAMESPACE,
			RELEASE_NAME,
			helmValuesPath,
			REPO_TYPE,
			context,
			workspace
		);
		verifyNoMoreInteractions(helmStrategy, argoCdStrategyProvider, argoCdStrategy);
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
			initByHelm,
			context,
			workspace
		);
	}
}
