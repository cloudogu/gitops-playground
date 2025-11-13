package com.cloudogu.gitops.utils.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest
import com.cloudogu.gitops.utils.NetworkingUtils

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
    void enable() {
        // Inject the test providers into the base class before running the real logic
        this.tenant = tenantProvider
        this.central = centralProvider

        // Mirror the production side effect: set namespace for internal SCMM
        if (this.config?.scm?.scmManager != null) {
            this.config.scm.scmManager.namespace = "${config.application.namePrefix}scm-manager".toString()
        }

        // === Run ONLY the repo setup logic (NO provider construction here) ===
        final String namePrefix = (config?.application?.namePrefix ?: "").trim()
        if (this.central) {
            setupRepos(this.central, namePrefix)
            setupRepos(this.tenant, namePrefix, false)
        } else {
            setupRepos(this.tenant, namePrefix, true)
        }
        create3thPartyDependencies(this.tenant, namePrefix)

    }

    @Override
    void validate() {}

    @Override
    GitProvider getTenant() { return tenantProvider }

    @Override
    GitProvider getCentral() { return centralProvider }

    @Override
    GitProvider getResourcesScm() { return centralProvider ?: tenantProvider }

}