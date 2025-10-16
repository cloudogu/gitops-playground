package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.scmmanager.ScmManagerMock

import static org.mockito.Mockito.mock

class GitHandlerForTests extends GitHandler{

    GitHandlerForTests(Config config) {
        super(config, mock(HelmStrategy),new FileSystemUtils(), new K8sClientForTest(config),new NetworkingUtils())
    }

    @Override
    GitProvider getTenant() {
        return new ScmManagerMock()
    }

    @Override
    GitProvider getResourcesScm() {
        return new ScmManagerMock()
    }

}