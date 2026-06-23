package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.tools.common.Tool

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(10)
class ScmManager extends Tool {

	String namespace

	private final Config config
	private final GitHandler gitHandler
	private final Deployer deployer

	ScmManager(Config config,
		GitHandler gitHandler,
		Deployer deployer) {
		this.config = config
		this.gitHandler = gitHandler
		this.deployer = deployer

		if (isInternalScmManagerConfigured()) {
			this.namespace = prefixedNamespace()
			this.config.scm.scmManager.namespace = this.namespace
		}
	}

	@Override
	boolean isEnabled() {
		isInternalScmManagerConfigured()
	}

	@Override
	void enable() {
		log.info("Starting internal SCM-Manager setup.")

		ScmManagerProvider scmManager = getTenantScmManager()

		ScmManagerSetup setup = new ScmManagerSetup(scmManager,
			deployer, config)

		setup.setupHelm()
		setup.waitForScmmAvailable()
		setup.configure()

		setupRepositoriesAfterDeployment()

		// Creating ArgoCD Application AFTER repos are created.
		// This fixes the bootstrap problem because the GitOps repository must exist first.
		setup.createArgocdApplication()

		log.info("Internal SCM-Manager setup finished.")
	}

	private boolean isInternalScmManagerConfigured() {
		config.scm.scmProviderType == ScmProviderType.SCM_MANAGER && config.scm.scmManager != null && config.scm.scmManager.internal
	}

	private String prefixedNamespace() {
		String prefix = config.application.namePrefix ?: ""
		String baseNamespace = config.scm.scmManager.namespace ?: "scm-manager"

		if (prefix && baseNamespace.startsWith(prefix)) {
			return baseNamespace
		}

		return "${prefix}${baseNamespace}".toString()
	}

	private ScmManagerProvider getTenantScmManager() {
		if (!(gitHandler.tenant instanceof ScmManagerProvider)) {
			throw new IllegalStateException("Tenant SCM provider is not an SCM-Manager. Actual provider: ${gitHandler.tenant?.class?.simpleName}")
		}

		return gitHandler.tenant as ScmManagerProvider
	}

	private void setupRepositoriesAfterDeployment() {
		final String namePrefix = (config?.application?.namePrefix ?: "").trim()

		GitHandler.setupRepos(gitHandler.tenant, namePrefix)

		if (gitHandler.central) {
			GitHandler.setupRepos(gitHandler.central, namePrefix)
		}
	}
}