package com.cloudogu.gitops.tools.common

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import groovy.transform.CompileStatic
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy
import static org.mockito.Mockito.mock

@CompileStatic
class AbstractToolTest {

    @Test
    void 'execute stores context and repository workspace and maps config before lifecycle execution'() {
        ToolForTest tool = new ToolForTest()
        DeploymentContext newContext = new ContextBuilder(new Config()).build()
        RepositoryWorkspace workspace = new RepositoryWorkspace(mock(GitRepo))

        tool.execute(newContext,
                workspace)

        assertThat(tool.context).isSameAs(newContext)
        assertThat(tool.repositoryWorkspace).isSameAs(workspace)
        assertThat(tool.configSeenDuringValidation).isTrue()
    }

    @Test
    void 'activation uses mapped tool config'() {
        ToolForTest tool = new ToolForTest({ DeploymentContext ignored -> false } as ToolConfigMapper<Boolean>)

        assertThat(tool.isEnabled(new ContextBuilder(new Config()).build())).isFalse()
    }

    @Test
    void 'mapped tools reject a missing mapper'() {
        assertThatThrownBy { new ToolForTest(null) }
                .isInstanceOf(NullPointerException)
                .hasMessage('Tool config mapper must not be null')
    }

    @Test
    void 'mapped tools reject a null mapper result'() {
        DeploymentContext context = new ContextBuilder(new Config()).build()
        ToolForTest tool = new ToolForTest({ DeploymentContext ignored -> null } as ToolConfigMapper<Boolean>)

        assertThatThrownBy { tool.isEnabled(context) }
                .isInstanceOf(NullPointerException)
                .hasMessageContaining('Tool config mapper returned null')
    }

    class ToolForTest extends AbstractMappedTool<Boolean> {

        Boolean configSeenDuringValidation

        ToolForTest() {
            this({ DeploymentContext ignored -> true } as ToolConfigMapper<Boolean>)
        }

        ToolForTest(ToolConfigMapper<Boolean> mapper) {
            super(mapper)
        }

        @Override
        protected boolean isEnabled(Boolean config) {
            return config
        }

        @Override
        void validate() {
            configSeenDuringValidation = toolConfig()
        }
    }
}
