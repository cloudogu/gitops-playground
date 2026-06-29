package com.cloudogu.gitops.application.orchestration

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.gitlab.GitlabProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
class GitHandler {

	DeploymentContext context
	NetworkingUtils networkingUtils
	K8sClient k8sClient

	GitProvider tenant
	GitProvider central

	GitHandler(DeploymentContext context,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils) {
		this.context = context
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils
	}

	protected Config getConfig() {
		return context.config
	}

	void validate() {
		if (config.scm.scmManager.url) {
			config.scm.scmManager.internal = false
			context.scmManagerDeploymentMode = DeploymentContext.DeploymentMode.EXTERNAL
			config.scm.scmManager.urlForJenkins = config.scm.scmManager.url
		} else {
			log.debug("Setting configs for internal SCM-Manager")

			config.scm.scmManager.internal = true
			context.scmManagerDeploymentMode = DeploymentContext.DeploymentMode.INTERNAL
			config.scm.scmManager.urlForJenkins = "http://scmm.${config.application.namePrefix}${config.scm.scmManager.namespace}.svc.cluster.local/scm"

		}

		config.scm.scmManager.gitOpsUsername = "${config.application.namePrefix}gitops"

		if (config.scm.gitlab.url) {
			config.scm.scmProviderType = ScmProviderType.GITLAB
			config.scm.scmManager = null

			if (!config.scm.gitlab.password || !config.scm.gitlab.parentGroupId) {
				throw new RuntimeException('GitLab configuration incomplete: please provide both password (PAT) and parentGroupId')
			}
		}
	}

	void prepareProviders() {
		this.tenant = createTenantScmProvider()

		if (context.isMultiTenant()) {
			this.central = createCentralScmProvider()
		}

		setupExternalRepositoriesIfPossible()
	}

	GitProvider getResourcesScm() {
		if (central) {
			return central
		}

		if (tenant) {
			return tenant
		}

		throw new IllegalStateException("No SCM provider found.")
	}

	private GitProvider createTenantScmProvider() {
		switch (config.scm.scmProviderType) {
			case ScmProviderType.GITLAB:
				return new GitlabProvider(context, config.scm.gitlab)

			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(context,
					config.scm.scmManager,
					k8sClient,
					networkingUtils)

			default:
				throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM: ${config.scm.scmProviderType}")
		}
	}

	private GitProvider createCentralScmProvider() {
		switch (config.multiTenant.scmProviderType) {
			case ScmProviderType.GITLAB:
				return new GitlabProvider(context, config.multiTenant.gitlab)

			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(context,
					config.multiTenant.scmManager,
					k8sClient,
					networkingUtils)

			default:
				throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.multiTenant.scmProviderType}")
		}
	}

	private void setupExternalRepositoriesIfPossible() {
		final String namePrefix = (config.application.namePrefix ?: "").trim()
		final boolean repositorySetupBlockedByInternalScmBootstrap = isRepositorySetupBlockedByInternalScmBootstrap()

		log.info("Evaluating repository setup: centralConfigured={}, tenantConfigured={}, namePrefix='{}', repositorySetupBlockedByInternalScmBootstrap={}",
			central != null,
			tenant != null,
			namePrefix,
			repositorySetupBlockedByInternalScmBootstrap)

		if (repositorySetupBlockedByInternalScmBootstrap) {
			log.info("Skipping repository setup because the configured internal SCM-Manager is not deployed yet. " +
				"Repository setup can continue immediately when an external SCM-Manager is configured. namePrefix='{}'",
				namePrefix)
			return
		}

		if (central) {
			log.info("Setting up central and tenant repositories. namePrefix='{}'", namePrefix)
			setupRepos(central, namePrefix)
			setupRepos(tenant, namePrefix)
		} else {
			log.info("Setting up tenant repositories only. namePrefix='{}'", namePrefix)
			setupRepos(tenant, namePrefix)
		}
	}

	private boolean isRepositorySetupBlockedByInternalScmBootstrap() {
		config.scm.scmProviderType == ScmProviderType.SCM_MANAGER && context.isInternalScmManager()
	}

	static void setupRepos(GitProvider gitProvider, String namePrefix = "") {
		gitProvider.createRepository(withOrgPrefix(namePrefix, "argocd/cluster-resources"),
			"GitOps repo for basic cluster-resources")
	}

	static String withOrgPrefix(String prefix, String repoPath) {
		if (!prefix) {
			return repoPath
		}

		return prefix + repoPath
	}
}