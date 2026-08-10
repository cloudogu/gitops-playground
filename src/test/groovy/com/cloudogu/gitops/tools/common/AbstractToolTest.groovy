package com.cloudogu.gitops.tools.common

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import groovy.transform.CompileStatic
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock

@CompileStatic
class AbstractToolTest {

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

    class ToolForTest extends AbstractMappedTool<Boolean> {

        ToolForTest() {
            super({ DeploymentContext ignored -> true } as ToolConfigMapper<Boolean>)
        }

        @Override
        protected boolean isEnabled(Boolean config) {
            return true
        }
    }
}
