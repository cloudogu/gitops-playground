package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.tools.common.Tool
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(10)
class ScmManager extends Tool {

	String namespace

	private final Config config
	private final GitHandler gitHandler
	private final Deployer deployer
	private final RepositoryProvisioning repositoryProvisioning

	ScmManager(
		Config config,
		GitHandler gitHandler,
		Deployer deployer,
		RepositoryProvisioning repositoryProvisioning
	) {
		this.config = config
		this.gitHandler = gitHandler
		this.deployer = deployer
		this.repositoryProvisioning = repositoryProvisioning
		this.namespace = configuredNamespace()
	}

	@Override
	boolean isEnabled() {
		isInternalScmManagerConfigured()
	}

	@Override
	void enable() {
		log.info('Starting internal SCM-Manager setup.')

		ScmManagerProvider scmManager = getResourcesScmManager()

		ScmManagerSetup setup = new ScmManagerSetup(
			scmManager,
			deployer,
			config
		)

		setup.setupHelm()
		setup.waitForScmmAvailable()
		setup.configure()

		repositoryProvisioning.bootstrapRepositoriesAfterScmManagerDeployment()

		// The SCM-Manager ArgoCD Application is created through ArgoCdApplicationStrategy.
		// The strategy writes into the shared RepositoryWorkspace and does not push itself.
		setup.createArgocdApplication()

		repositoryProvisioning.publishClusterResourcesRepositoryChanges(
			'scm-manager',
			'Add SCM-Manager ArgoCD application'
		)

		log.info('Internal SCM-Manager setup finished.')
	}

	private boolean isInternalScmManagerConfigured() {
		if (config.multiTenant.useDedicatedInstance) {
			return config.multiTenant.scmProviderType == ScmProviderType.SCM_MANAGER &&
				config.multiTenant.scmManager != null &&
				config.multiTenant.scmManager.internal
		}

		return config.scm.scmProviderType == ScmProviderType.SCM_MANAGER &&
			config.scm.scmManager != null &&
			config.scm.scmManager.internal
	}

	private String configuredNamespace() {
		if (config.multiTenant.useDedicatedInstance) {
			return config.multiTenant.scmManager?.namespace ?: 'scm-manager'
		}

		return config.scm.scmManager?.namespace ?: 'scm-manager'
	}

	private ScmManagerProvider getResourcesScmManager() {
		GitProvider resourcesScm = gitHandler.getResourcesScm()

		if (!(resourcesScm instanceof ScmManagerProvider)) {
			throw new IllegalStateException(
				"Resources SCM provider is not an SCM-Manager. Actual provider: ${resourcesScm?.class?.simpleName}"
			)
		}

		return resourcesScm as ScmManagerProvider
	}
}