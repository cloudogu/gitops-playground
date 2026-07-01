package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
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
	private final RepositoryProvisioning repositoryProvisioning

	ScmManager(DeploymentContext context,
		GitHandler gitHandler,
		Deployer deployer,
		RepositoryProvisioning repositoryProvisioning,
		K8sClient k8sClient) {
		this.context = context
		this.gitHandler = gitHandler
		this.deployer = deployer
		this.repositoryProvisioning = repositoryProvisioning
		this.k8sClient = k8sClient

		if (context.isInternalScmManager()) {
			this.namespace = prefixedNamespace()
			this.config.scm.scmManager.namespace = this.namespace
		}
	}

	@Override
	boolean isEnabled() {
		return context.isInternalScmManager()
	}

	@Override
	void enable() {
		log.info('Starting internal SCM-Manager setup.')

		ScmManagerProvider scmManager = getTenantScmManager()

		ScmManagerSetup setup = new ScmManagerSetup(scmManager,
			deployer,
			context)

		setup.setupHelm()
		setup.waitForScmmAvailable()
		setup.configure()

		repositoryProvisioning.bootstrapRepositoriesAfterScmManagerDeployment()

		// The SCM-Manager ArgoCD Application is created through ArgoCdApplicationStrategy.
		// The strategy writes into the shared RepositoryWorkspace and does not push itself.
		setup.createArgocdApplication()

		log.info('Internal SCM-Manager setup finished.')
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
		GitProvider tenantScm = gitHandler.tenant

		if (!(tenantScm instanceof ScmManagerProvider)) {
			throw new IllegalStateException("Tenant SCM provider is not an SCM-Manager. Actual provider: ${tenantScm?.class?.simpleName}")
		}

		return tenantScm as ScmManagerProvider
	}
}