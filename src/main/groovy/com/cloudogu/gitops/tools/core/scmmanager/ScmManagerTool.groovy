package com.cloudogu.gitops.tools.core

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.deployment.HelmStrategy
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManager
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.core.scmmanager.ScmManagerSetup
import com.cloudogu.gitops.utils.FileSystemUtils

import io.micronaut.core.annotation.Order

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(65)
class ScmManagerTool extends Tool {

	private static final String TOOL_NAME = 'scm-manager'
	private static final String SCM_MANAGER_SOURCE_DIR = 'argocd/cluster-resources/apps/scm-manager'

	private final Config config
	private final GitHandler gitHandler
	private final HelmStrategy helmStrategy
	private final FileSystemUtils fileSystemUtils
	private final RepositoryProvisioning repositoryProvisioning

	ScmManagerTool(Config config,
		GitHandler gitHandler,
		HelmStrategy helmStrategy,
		FileSystemUtils fileSystemUtils,
		RepositoryProvisioning repositoryProvisioning) {
		this.config = config
		this.gitHandler = gitHandler
		this.helmStrategy = helmStrategy
		this.fileSystemUtils = fileSystemUtils
		this.repositoryProvisioning = repositoryProvisioning
	}

	@Override
	boolean isEnabled() {
		config.scm.scmProviderType == ScmProviderType.SCM_MANAGER && config.scm.scmManager?.internal
	}

	@Override
	void enable() {
		log.info('Starting internal SCM-Manager setup.')

		RepositoryWorkspace workspace = repositoryProvisioning.provideWorkspace()

		prepareWorkspace(workspace)

		ScmManager scmManager = getTenantScmManager()

		ScmManagerSetup setup = new ScmManagerSetup(config,
			config.scm.scmManager,
			helmStrategy,
			scmManager,
			fileSystemUtils)

		setup.setupHelm()
		setup.waitForScmmAvailable()
		setup.configure()

		repositoryProvisioning.publishInitialStateAfterScmManagerDeployment()

		log.info('Internal SCM-Manager setup finished.')
	}

	private void prepareWorkspace(RepositoryWorkspace workspace) {
		log.debug('Preparing SCM-Manager workspace resources.')

		String targetDir = workspace.clusterAppDir(TOOL_NAME)
		Path.of(targetDir).toFile().mkdirs()

		fileSystemUtils.copyDirectory(SCM_MANAGER_SOURCE_DIR,
			targetDir)
	}

	private ScmManager getTenantScmManager() {
		if (!(gitHandler.tenant instanceof ScmManager)) {
			throw new IllegalStateException("Tenant SCM provider is not an SCM-Manager. Actual provider: ${gitHandler.tenant?.class?.simpleName}")
		}

		return gitHandler.tenant as ScmManager
	}
}