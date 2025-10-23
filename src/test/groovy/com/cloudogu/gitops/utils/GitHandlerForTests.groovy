package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.providers.GitProvider

import static org.mockito.Mockito.mock

class GitHandlerForTests extends GitHandler {
    private final GitProvider tenantProvider
    private final GitProvider centralProvider

    GitHandlerForTests(Config config, GitProvider tenantProvider, GitProvider centralProvider = null) {
        super(config, mock(HelmStrategy), new FileSystemUtils(), new K8sClientForTest(config), new NetworkingUtils())
        this.tenantProvider = tenantProvider
        this.centralProvider = centralProvider
    }

    @Override
    void enable() {}

    @Override
    void validate() {}

    @Override
    GitProvider getTenant() { return tenantProvider }

    @Override
    GitProvider getCentral() { return centralProvider }

    @Override
    GitProvider getResourcesScm() { return centralProvider ?: tenantProvider }

}