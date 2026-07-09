package com.cloudogu.gitops.tools.common

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo

import groovy.transform.CompileStatic

import org.junit.jupiter.api.Test

@CompileStatic
class ToolTest {

	@Test
	void 'execute stores context and repository workspace'() {
		ToolForTest tool = new ToolForTest()
		DeploymentContext newContext = new ContextBuilder(new Config()).build()
		RepositoryWorkspace workspace = new RepositoryWorkspace(mock(GitRepo))

		tool.execute(newContext,
			workspace)

		assertThat(tool.context).isSameAs(newContext)
		assertThat(tool.repositoryWorkspace).isSameAs(workspace)
	}

	class ToolForTest extends Tool {

		@Override
		boolean isEnabled(DeploymentContext context) {
			return true
		}
	}
}