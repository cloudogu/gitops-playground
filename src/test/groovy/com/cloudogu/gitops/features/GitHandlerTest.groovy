package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import org.junit.jupiter.api.Test

import static org.mockito.Mockito.mock


class GitHandlerTest {


    Config config = new Config().fromMap([
            application: [
                    namePrefix: ''
            ],
            scm:[
                    scmManager:[
                            url: ''
                    ],
                    gitlab:[
                            url: ''
                    ],
                    gitOpsUsername: ''
            ]
    ])

    HelmStrategy helmStrategy = mock(HelmStrategy.class)
    GitHandler gitHandler
    K8sClient k8sClient = mock(K8sClient.class)


    //TODO Anna
/*    @Test
    void 'default rollout'() {
        //rolls out scmManager as tenant
        createGitHandler().enable()
        assert(true)
    }

    @Test
    void 'gets correct getResourcesScm - cenant'() {
        //only tenant is set-> getResourceScm returns tenant
        createGitHandler().getResourcesScm()

    }

    @Test
    void 'gets correct getResourcesScm - central'() {
        //both scms are set-> getResourceScm returns central
        createGitHandler().getResourcesScm()

    }



    @Test
    void 'throws error if gitlab url without token is used'() {
        config.scm.scmProviderType = ScmProviderType.GITLAB
        config.scm.gitlab.url = "test.de"
        createGitHandler().enable()

    }*/

    private GitHandler createGitHandler() {
        this.gitHandler = new GitHandler(config, helmStrategy, new FileSystemUtils(), k8sClient, new NetworkingUtils()) {

        }
    }
}