package com.cloudogu.gitops.application.orchestration

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

	Config config
	NetworkingUtils networkingUtils
	K8sClient k8sClient

	GitProvider tenant
	GitProvider central

	GitHandler(Config config,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils) {
		this.config = config
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils
	}

	void validate() {
		if (config.scm.scmManager.url) {
			config.scm.scmManager.internal = false
			config.scm.scmManager.urlForJenkins = config.scm.scmManager.url
		} else {
			log.debug("Setting configs for internal SCM-Manager")

			config.scm.scmManager.internal = true
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

		if (config.multiTenant.useDedicatedInstance) {
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
				return new GitlabProvider(config, config.scm.gitlab)

			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(config,
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
				return new GitlabProvider(config, config.multiTenant.gitlab)

			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(config,
					config.multiTenant.scmManager,
					k8sClient,
					networkingUtils)

			default:
				throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.multiTenant.scmProviderType}")
		}
	}

	private void setupExternalRepositoriesIfPossible() {
		final String namePrefix = (config?.application?.namePrefix ?: "").trim()

		if (shouldSkipRepositorySetupForInternalScmManager()) {
			log.debug("Skipping repository setup in GitHandler because internal SCM-Manager is not deployed yet.")
			return
		}

		if (central) {
			setupRepos(central, namePrefix)
			setupRepos(tenant, namePrefix)
		} else {
			setupRepos(tenant, namePrefix)
		}
	}

	private boolean shouldSkipRepositorySetupForInternalScmManager() {
		config.scm.scmProviderType == ScmProviderType.SCM_MANAGER && config.scm.scmManager?.internal
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