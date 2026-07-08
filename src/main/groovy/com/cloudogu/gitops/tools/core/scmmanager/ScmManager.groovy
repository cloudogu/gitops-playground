package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.tools.common.Tool

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(10)
class ScmManager extends Tool {

	String namespace

	private final ImagePullSecretCreator imagePullSecretCreator
	private ScmManagerSetup setup

	ScmManager(GitHandler gitHandler,
		Deployer deployer,
		ImagePullSecretCreator imagePullSecretCreator) {
		this.gitHandler = gitHandler
		this.deployer = deployer
		this.imagePullSecretCreator = imagePullSecretCreator
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.isInternalScmManager()
	}

	@Override
	protected void preDeploy() {
		log.info('Preparing internal SCM-Manager deployment.')

		prepareNamespace()
		imagePullSecretCreator.createIfRequired(config, namespace)

		ScmManagerProvider scmManager = getTenantScmManager()

		this.setup = new ScmManagerSetup(scmManager,
			deployer,
			context,
			repositoryWorkspace)
	}

	@Override
	protected void deploy() {
		log.info('Deploying internal SCM-Manager.')

		setup.setupHelm()
		setup.waitForScmmAvailable()
	}

	@Override
	protected void postDeploy() {
		log.info('Configuring internal SCM-Manager after deployment.')

		setup.configure()
		
		// Special bootstrap step:
		// Creates/initializes the remote repositories and performs the initial push.
		setup.bootstrapAfterScmManagerDeployment()

		// The SCM-Manager ArgoCD Application is created through ArgoCdApplicationStrategy.
		// The strategy writes into the shared RepositoryWorkspace and does not push itself.
		setup.createArgocdApplication()
	}

	@Override
	protected void publishChanges() {
		publishClusterResourcesChanges('SCM-Manager')

		log.info('Internal SCM-Manager deployment finished.')
	}

	private void prepareNamespace() {
		this.namespace = prefixedNamespace(context)
		this.config.scm.scmManager.namespace = this.namespace
	}

	@Override
	protected String activeNamespace(DeploymentContext context) {
		return prefixedNamespace(context)
	}

	private String prefixedNamespace(DeploymentContext context) {
		String prefix = context.config.application.namePrefix ?: ''
		String baseNamespace = context.config.scm.scmManager.namespace ?: 'scm-manager'

		if (prefix && baseNamespace.startsWith(prefix)) {
			return baseNamespace
		}

		return "${prefix}${baseNamespace}".toString()
	}

	private ScmManagerProvider getTenantScmManager() {
		GitProvider tenantScm = gitHandler.tenant

		if (!(tenantScm instanceof ScmManagerProvider)) {
			throw new IllegalStateException("Tenant SCM provider is not an SCM-Manager. Actual provider: ${tenantScm?.class?.simpleName}")
		}

		return tenantScm as ScmManagerProvider
	}
}