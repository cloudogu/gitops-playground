package com.cloudogu.gitops.tools.core

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.deployment.HelmStrategy
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManager
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.core.scmmanager.ScmManagerSetup
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(65)
class ScmManagerTool extends Tool {

    private final Config config
    private final GitHandler gitHandler
    private final HelmStrategy helmStrategy
    private final FileSystemUtils fileSystemUtils

    ScmManagerTool(
            Config config,
            GitHandler gitHandler,
            HelmStrategy helmStrategy,
            FileSystemUtils fileSystemUtils
    ) {
        this.config = config
        this.gitHandler = gitHandler
        this.helmStrategy = helmStrategy
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        config.scm.scmProviderType == ScmProviderType.SCM_MANAGER &&
                config.scm.scmManager?.internal
    }

    @Override
    void enable() {
        log.info("Starting internal SCM-Manager setup.")

        ScmManager scmManager = getTenantScmManager()

        ScmManagerSetup setup = new ScmManagerSetup(
                config,
                config.scm.scmManager,
                helmStrategy,
                scmManager,
                fileSystemUtils
        )

        setup.setupHelm()
        setup.waitForScmmAvailable()
        setup.configure()

        setupRepositoriesAfterDeployment()

        log.info("Internal SCM-Manager setup finished.")
    }

    private ScmManager getTenantScmManager() {
        if (!(gitHandler.tenant instanceof ScmManager)) {
            throw new IllegalStateException(
                    "Tenant SCM provider is not an SCM-Manager. Actual provider: ${gitHandler.tenant?.class?.simpleName}"
            )
        }

        return gitHandler.tenant as ScmManager
    }

    private void setupRepositoriesAfterDeployment() {
        final String namePrefix = (config?.application?.namePrefix ?: "").trim()

        GitHandler.setupRepos(gitHandler.tenant, namePrefix)

        if (gitHandler.central) {
            GitHandler.setupRepos(gitHandler.central, namePrefix)
        }
    }
}