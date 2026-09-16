package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HelmStrategyTest {

	private final HelmClient helmClient = mock(HelmClient.class);

	@Test
	void deploysFeatureUsingHelmClient() throws IOException {
		Path valuesYaml = Files.createTempFile("", "");
		DeploymentContext context = new ContextBuilder(createConfig()).build();

		createStrategy().deployFeature(
			"repoURL",
			"repoName",
			"chart",
			"version",
			"foo-namespace",
			"releaseName",
			valuesYaml,
			DeploymentStrategy.RepoType.HELM,
			context,
			null
		);

		verify(helmClient).addRepo("repoName", "repoURL");
		verify(helmClient).upgrade(
			"releaseName",
			"repoName/chart",
			Map.of(
				"namespace", "foo-namespace",
				"version", "version",
				"values", valuesYaml.toString()
			)
		);
	}

	@Test
	void failsToDeployFromGit() {
		DeploymentContext context = new ContextBuilder(createConfig()).build();

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> createStrategy().deployFeature(
				"http://repoURL",
				"repoName",
				"chart",
				"version",
				"namespace",
				"releaseName",
				Path.of("values.yaml"),
				DeploymentStrategy.RepoType.GIT,
				context,
				null
			)
		);

		assertThat(exception.getMessage()).isEqualTo(
			"Unable to deploy helm chart via Helm CLI from Git URL, because helm does not support this out of the box.\n" +
				"Repo URL: http://repoURL"
		);
	}

	protected HelmStrategy createStrategy() {
		return new HelmStrategy(helmClient);
	}

	private Config createConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("foo-");
		return config;
	}
}
