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
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
@Order(10)
class ScmManager extends Tool {

	String namespace

	private final GitHandler gitHandler
	private final Deployer deployer
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
		createImagePullSecret()

		ScmManagerProvider scmManager = getTenantScmManager()

		this.setup = new ScmManagerSetup(scmManager,
			deployer,
			context,
			repositoryWorkspace)
	}

	@Override
	protected void deploy() {
		log.info('Deploying internal SCM-Manager.')

		/*
		 * SCM-Manager must first be installed imperatively through Helm,
		 * because its Git repositories do not exist yet.
		 */
		setup.setupHelm()
		setup.waitForScmmAvailable()
	}

	@Override
	protected void postDeploy() {
		log.info('Configuring internal SCM-Manager after deployment.')

		setup.configure()

		/*
		 * Creates and initializes the remote repositories and prepares
		 * the local workspace from the remote main branch.
		 */
		setup.prepareBootstrapRepositoriesAfterScmManagerDeployment()

		/*
		 * Creates the SCM-Manager ArgoCD Application through the
		 * existing ArgoCdApplicationStrategy.
		 *
		 * This is deliberately done after SCM-Manager is available
		 * and the bootstrap repositories have been prepared.
		 */
		setup.createArgocdApplication()
	}

	@Override
	protected void publishChanges() {
		/*
		 * Pushes the complete bootstrap state, including the generated
		 * SCM-Manager GitOps resources.
		 */
		setup.pushBootstrapRepositoriesAfterScmManagerDeployment()

		log.info('Internal SCM-Manager setup finished.')
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		return prefixedNamespace(context)
	}

	private void prepareNamespace() {
		this.namespace = resolveNamespace(context)

		/*
		 * Existing transitional behavior:
		 * downstream SCM-Manager components currently expect the
		 * effective namespace in the central Config.
		 *
		 * This mutation can be removed later when ScmManagerConfig
		 * is introduced and the effective namespace is passed
		 * explicitly.
		 */
		this.config.scm.scmManager.namespace = namespace
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(config,
			namespace)
	}

	private String prefixedNamespace(DeploymentContext context) {
		String prefix =
			context.config.application.namePrefix ?: ''

		String baseNamespace =
			context.config.scm.scmManager.namespace ?: 'scm-manager'

		/*
		 * The Config currently stores the effective namespace after
		 * prepareNamespace() has run. Avoid adding the prefix twice
		 * on subsequent namespace resolutions.
		 */
		if (prefix && baseNamespace.startsWith(prefix)) {
			return baseNamespace
		}

		return "${prefix}${baseNamespace}".toString()
	}

	private ScmManagerProvider getTenantScmManager() {
		GitProvider tenantScm = gitHandler.tenant

		if (!(tenantScm instanceof ScmManagerProvider)) {
			throw new IllegalStateException('Tenant SCM provider is not an SCM-Manager. ' + 'Actual provider: ' + "${tenantScm?.class?.simpleName}")
		}

		return tenantScm as ScmManagerProvider
	}
}