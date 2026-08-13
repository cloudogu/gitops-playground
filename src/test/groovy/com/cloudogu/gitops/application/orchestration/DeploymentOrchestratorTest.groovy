package com.cloudogu.gitops.application.orchestration

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.tools.common.AbstractTool
import org.junit.jupiter.api.Test
import org.mockito.InOrder

import static org.mockito.Mockito.inOrder
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.never
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

class DeploymentOrchestratorTest {

    @Test
    void 'deploys enabled tools in configured order with context and workspace'() {
        DeploymentContext context = new ContextBuilder(new Config()).build()
        RepositoryWorkspace workspace = new RepositoryWorkspace(mock(GitRepo))
        AbstractTool firstTool = mock(AbstractTool)
        AbstractTool secondTool = mock(AbstractTool)
        AbstractTool disabledTool = mock(AbstractTool)

        when(firstTool.isEnabled(context)).thenReturn(true)
        when(secondTool.isEnabled(context)).thenReturn(true)

        new DeploymentOrchestrator([firstTool,
                                    disabledTool,
                                    secondTool]).deployTools(context,
                workspace)

        InOrder order = inOrder(firstTool, secondTool)
        order.verify(firstTool).execute(context, workspace)
        order.verify(secondTool).execute(context,
                workspace)

        verify(disabledTool, never()).execute(context,
                workspace)
    }
}