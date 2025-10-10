package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import static org.mockito.Mockito.mock

class GitHandlerTest {

    Config testConfig = Config.fromMap([
            application: [
                    namePrefix: ''
            ]
    ])

    HelmClient helmClient = new HelmClient()
    NetworkingUtils networkingUtils = mock(NetworkingUtils.class)
    K8sClient k8sClient = mock(K8sClient)



    private GitHandler createGitHandler() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        new GitHandler(testConfig,helmClient,new FileSystemUtils() {

        }, deploymentStrategy, k8sClient, airGappedUtils)
    }
}