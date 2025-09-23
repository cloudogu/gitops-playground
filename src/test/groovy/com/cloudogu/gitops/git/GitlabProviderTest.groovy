package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.git.providers.gitlab.GitlabProvider
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.GroupApi
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mock


class GitlabProviderTest {

    @Mock
    private GitLabApi gitLabApiMock

    @Mock
    private GroupApi groupApiMock

    @Mock
    GitlabConfig

    Config config = new Config(
            application: new Config.ApplicationSchema(
                    namePrefix: "foo-")
    )

//    GitlabConfig gitlabConfig = new GitlabConfig(
//            url: 'testUrl.de',
//            credentials: new Credentials("TestUserName", "TestPassword"),
//            parentGroup: 19
//    )

    @BeforeEach
    void setUp() {
        when(gitLabApiMock.getGroupApi()).thenReturn(groupApiMock)
        gitlab = new GitlabProvider(this.config, gitlabConfig) {
            {
                this.gitlabApi = gitLabApiMock;
            }
        }
    }
}