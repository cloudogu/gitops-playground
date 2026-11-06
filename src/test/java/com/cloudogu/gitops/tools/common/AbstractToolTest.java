package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.utils.MapUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
		Files.writeString(template, "<#if false>\ninstallCRDs: false\n</#if>");
		Map<String, Object> overrides = Map.of(
			"rbac", Map.of("serviceAccountTokenCreate", false)
		);

		// when
		Map<String, Object> templateValues = AbstractTool.templateToMap(template.toString(), Map.of());
		Map<String, Object> mergedValues = MapUtils.deepMerge(overrides, templateValues);

		// then
		assertThat(mergedValues).containsEntry("rbac", overrides.get("rbac"));
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
