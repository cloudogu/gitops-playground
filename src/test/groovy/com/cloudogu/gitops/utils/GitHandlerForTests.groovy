package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler

import static org.mockito.Mockito.mock

class GitHandlerForTests extends GitHandler{

    GitHandlerForTests(Config config) {
        super(config, mock(HelmStrategy),new FileSystemUtils(), new K8sClientForTest(config),new NetworkingUtils())
    }
}