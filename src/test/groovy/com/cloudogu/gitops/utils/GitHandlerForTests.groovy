package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.scmmanager.ScmManager

import static org.mockito.Mockito.mock

class GitHandlerForTests extends GitHandler{

    ScmManagerConfig scmManagerConfig

    GitHandlerForTests(Config config,ScmManagerConfig scmManagerConfig) {
        super(config, mock(HelmStrategy),new FileSystemUtils(), new K8sClientForTest(config),new NetworkingUtils())
        this.scmManagerConfig=scmManagerConfig
    }

    @Override
    GitProvider getResourcesScm() {
        return new ScmManager(config,scmManagerConfig,this.k8sClient,networkingUtils)
    }

}