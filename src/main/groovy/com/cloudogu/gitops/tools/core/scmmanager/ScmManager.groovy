package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.common.ToolWithImage

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(10)
class ScmManager extends Tool implements ToolWithImage {

	String namespace

	final K8sClient k8sClient

	ScmManager(GitHandler gitHandler,
		Deployer deployer,
		K8sClient k8sClient) {
		this.gitHandler = gitHandler
		this.deployer = deployer
		this.k8sClient = k8sClient
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.isInternalScmManager()
	}

	@Override
	protected void prepare() {
		prepareNamespace()
	}

	@Override
	void enable() {
		log.info('Starting internal SCM-Manager setup.')

		ScmManagerProvider scmManager = getTenantScmManager()

		ScmManagerSetup setup = new ScmManagerSetup(scmManager,
			deployer,
			context,
			repositoryWorkspace)

		setup.setupHelm()
		setup.waitForScmmAvailable()
		setup.configure()
		setup.bootstrapAfterScmManagerDeployment()

		// The SCM-Manager ArgoCD Application is created through ArgoCdApplicationStrategy.
		// The strategy writes into the shared RepositoryWorkspace and does not push itself.
		setup.createArgocdApplication()

		repositoryWorkspace.commitAndPushClusterResourcesChanges(
			"Update SCM-Manager GitOps resources"
		)


		log.info('Internal SCM-Manager setup finished.')
	}

	private void prepareNamespace() {
		this.namespace = activeNamespace(context)
		this.config.scm.scmManager.namespace = this.namespace
	}

	@Override
	protected String activeNamespace(DeploymentContext context) {
		return prefixedNamespace(context)
	}

	private String prefixedNamespace(DeploymentContext context) {
		String prefix = context.config.application.namePrefix ?: ""
		String baseNamespace = context.config.scm.scmManager.namespace ?: "scm-manager"

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