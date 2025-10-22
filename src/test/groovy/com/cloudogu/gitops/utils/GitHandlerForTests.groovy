package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.scmmanager.ScmManagerMock

import static org.mockito.Mockito.mock

class GitHandlerForTests extends GitHandler{
    private final GitProvider provider

    GitHandlerForTests(Config config, GitProvider provider) {
        // adapt K8sClientForTest ctor args to your local signature
        super(config, mock(HelmStrategy), new FileSystemUtils(), new K8sClientForTest(config), new NetworkingUtils())
        this.provider = provider
    }

    /** Prevent the base class from constructing real providers from config */
    @Override
    void enable() {
        // no-op: keep using the injected test provider
    }

    /** If base validate() enforces tokens/urls you don't set in tests, you can also no-op it: */
    @Override
    void validate() {
        // no-op for tests (optional; only if base validate() throws in your scenario)
    }

    @Override
    GitProvider getTenant() {
        return provider
    }

    @Override
    GitProvider getCentral() {
        return provider
    }

    @Override
    GitProvider getResourcesScm() {
        return provider
    }

}