package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.utils.FileSystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractToolTest {

	@Test
	void executeStoresContextAndRepositoryWorkspaceAndMapsConfigBeforeLifecycleExecution() {
		ToolForTest tool = new ToolForTest();
		DeploymentContext newContext = new ContextBuilder(new Config()).build();
		RepositoryWorkspace workspace = new RepositoryWorkspace(mock(GitRepo.class));

		tool.execute(newContext, workspace);

		assertThat(tool.context).isSameAs(newContext);
		assertThat(tool.repositoryWorkspace).isSameAs(workspace);
		assertThat(tool.configSeenDuringValidation).isTrue();
	}

	@Test
	void activationUsesMappedToolConfig() {
		ToolForTest tool = new ToolForTest(ignored -> false);

		assertThat(tool.isEnabled(new ContextBuilder(new Config()).build())).isFalse();
	}

	@Test
	void mappedToolsRejectAMissingMapper() {
		assertThatThrownBy(() -> new ToolForTest(null))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("Tool config mapper must not be null");
	}

	@Test
	void mappedToolsRejectANullMapperResult() {
		DeploymentContext context = new ContextBuilder(new Config()).build();
		ToolForTest tool = new ToolForTest(ignored -> null);

		assertThatThrownBy(() -> tool.isEnabled(context))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("Tool config mapper returned null");
	}

	@Test
	void mergesHelmOverridesWhenValuesTemplateRendersEmpty(@TempDir Path tempDir) throws IOException {
		// given
		Path template = tempDir.resolve("values.ftl.yaml");
		Files.writeString(template, "<#if false>\nchartSetting: true\n</#if>");
		ToolForTest tool = toolWithMockHelmDependencies();
		Map<String, Object> overrides = Map.of("additionalSetting", true);
		when(tool.fileSystemUtils.writeTempFile(anyMap())).thenReturn(tempDir.resolve("merged-values.yaml"));

		// when
		tool.deployHelmChart(
			"test-feature", "test-release", "test-namespace", helmConfig(overrides), template.toString(),
			new ContextBuilder(new Config()).build()
		);

		// then
		verify(tool.fileSystemUtils).writeTempFile(overrides);
	}

	@Test
	void mergesHelmOverridesIntoImmutableValues(@TempDir Path tempDir) {
		// given
		ToolForTest tool = toolWithMockHelmDependencies();
		Path valuesFile = tempDir.resolve("values.yaml");
		Map<String, Object> originalValues = Map.of("rbac", Map.of("create", true));
		Map<String, Object> overrides = Map.of("rbac", Map.of("serviceAccountTokenCreate", false));
		when(tool.fileSystemUtils.readYaml(valuesFile)).thenReturn(originalValues);
		when(tool.fileSystemUtils.writeTempFile(anyMap())).thenReturn(tempDir.resolve("merged-values.yaml"));

		// when
		tool.deployHelmChart(
			"test-feature", "test-release", "test-namespace", helmConfig(overrides), valuesFile.toString(),
			new ContextBuilder(new Config()).build()
		);

		// then
		verify(tool.fileSystemUtils).writeTempFile(Map.of(
			"rbac", Map.of("create", true, "serviceAccountTokenCreate", false)
		));
		assertThat(originalValues).containsEntry("rbac", Map.of("create", true));
	}

	private static ToolForTest toolWithMockHelmDependencies() {
		ToolForTest tool = new ToolForTest();
		tool.fileSystemUtils = mock(FileSystemUtils.class);
		tool.deployer = mock(Deployer.class);
		return tool;
	}

	private static HelmChartConfig helmConfig(Map<String, Object> overrides) {
		return HelmChartConfig.builder()
			.repoURL("https://example.invalid/charts")
			.chart("test-chart")
			.version("1.0.0")
			.values(overrides)
			.build();
	}

	private static class ToolForTest extends AbstractMappedTool<Boolean> {

		private Boolean configSeenDuringValidation;

		ToolForTest() {
			this(ignored -> true);
		}

		ToolForTest(ToolConfigMapper<Boolean> mapper) {
			super(mapper);
		}

		@Override
		protected boolean isEnabled(Boolean config) {
			return config;
		}

		@Override
		public void validate() {
			configSeenDuringValidation = toolConfig();
		}
	}
}
