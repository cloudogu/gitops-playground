package com.cloudogu.gitops.application.orchestration

import static org.mockito.Mockito.inOrder
import static org.mockito.Mockito.mock

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.tools.common.Tool

import org.junit.jupiter.api.Test
import org.mockito.InOrder

class DeploymentOrchestratorTest {

	@Test
	void 'executes tools in configured order with context and workspace'() {
		DeploymentContext context = new ContextBuilder(new Config()).build()
		RepositoryWorkspace workspace = new RepositoryWorkspace(mock(GitRepo))
		Tool firstTool = mock(Tool)
		Tool secondTool = mock(Tool)

		new DeploymentOrchestrator([firstTool,
		                            secondTool]).execute(context,
			workspace)

		InOrder order = inOrder(firstTool,
			secondTool)
		order.verify(firstTool).validate()
		order.verify(secondTool).validate()
		order.verify(firstTool).execute(context,
			workspace)
		order.verify(secondTool).execute(context,
			workspace)
	}
}