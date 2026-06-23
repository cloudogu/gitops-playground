package com.cloudogu.gitops.tools.core

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
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
	private final FileSystemUtils fileSystemUtils
	private final RepositoryProvisioning repositoryProvisioning
	private final Deployer deployer

	ScmManagerTool(Config config,
		GitHandler gitHandler,
		Deployer deployer,
		FileSystemUtils fileSystemUtils,
		RepositoryProvisioning repositoryProvisioning) {
		this.config = config
		this.gitHandler = gitHandler
		this.deployer = deployer
		this.fileSystemUtils = fileSystemUtils
		this.repositoryProvisioning = repositoryProvisioning
	}

	@Override
	boolean isEnabled() {
		//		config.scm.scmProviderType == ScmProviderType.SCM_MANAGER && config.scm.scmManager?.internal
		return false
	}

	@Override
	void enable() {
		log.info('Starting internal SCM-Manager setup.')

		RepositoryWorkspace workspace = repositoryProvisioning.provideWorkspace()

		prepareWorkspace(workspace)

		ScmManagerProvider scmManager = getTenantScmManager()

		ScmManagerSetup setup = new ScmManagerSetup(scmManager,
			deployer, config)

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

	private ScmManagerProvider getTenantScmManager() {
		if (!(gitHandler.tenant instanceof ScmManagerProvider)) {
			throw new IllegalStateException("Tenant SCM provider is not an SCM-Manager. Actual provider: ${gitHandler.tenant?.class?.simpleName}")
		}

		return gitHandler.tenant as ScmManagerProvider
	}
}