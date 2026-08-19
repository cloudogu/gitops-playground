package com.cloudogu.gitops.infrastructure.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.utils.FileSystemUtils
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class GitRepoFactoryTest {

    Config config = Config.fromMap([application: [gitName : "Cloudogu",
                                                  gitEmail: "hello@cloudogu.com"],
                                    scm        : [scmManager: [username: "dont-care-username",
                                                               password: "dont-care-password"]]])

    GitRepoFactory factory = new GitRepoFactory(config, new FileSystemUtils())

    @Test
    void 'Creates repo with empty name-prefix'() {
        def repo = factory.create('expectedRepoTarget', new ScmManagerProviderMock())

        assertThat(repo.repoTarget).isEqualTo('expectedRepoTarget')
    }

    @Test
    void 'Creates repo with name-prefix'() {
        config.application.namePrefix = 'abc-'

        def repo = factory.create('expectedRepoTarget', new ScmManagerProviderMock())

        assertThat(repo.repoTarget).isEqualTo('abc-expectedRepoTarget')
    }

    @Test
    void 'Creates repo with name-prefix when in namespace 3rd-party-deps'() {
        config.application.namePrefix = 'abc-'

        def repo = factory.create("${GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/foo", new ScmManagerProviderMock())

        assertThat(repo.repoTarget).isEqualTo("${config.application.namePrefix}${GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/foo".toString())
    }
}
