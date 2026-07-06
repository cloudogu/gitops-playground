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

	NetworkingUtils networkingUtils
	K8sClient k8sClient

	GitProvider tenant
	GitProvider central

	GitHandler(K8sClient k8sClient,
		NetworkingUtils networkingUtils) {
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils
	}

	void validate(DeploymentContext context) {
		Config config = context.config

		if (config.scm.gitlab.url) {
			config.scm.scmProviderType = ScmProviderType.GITLAB
			config.scm.scmManager = null

			if (!config.scm.gitlab.password || !config.scm.gitlab.parentGroupId) {
				throw new RuntimeException('GitLab configuration incomplete: please provide both password (PAT) and parentGroupId')
			}
			return
		}

		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager.gitOpsUsername = "${config.application.namePrefix}gitops"
	}

	void prepareProviders(DeploymentContext context) {
		this.tenant = createTenantScmProvider(context)

		if (context.isMultiTenant()) {
			this.central = createCentralScmProvider(context)
		}
	}

	GitProvider getResourcesScm() {
		if (central) {
			return central
		}

		if (tenant) {
			return tenant
		}

		throw new IllegalStateException('No SCM provider found.')
	}

	private GitProvider createTenantScmProvider(DeploymentContext context) {
		Config config = context.config

		switch (config.scm.scmProviderType) {
			case ScmProviderType.GITLAB:
				return new GitlabProvider(context, config.scm.gitlab)
			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(context,
					config.scm.scmManager,
					k8sClient,
					networkingUtils,
					config.application.namePrefix ?: '')

			default:
				throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM: ${config.scm.scmProviderType}")
		}
	}

	private GitProvider createCentralScmProvider(DeploymentContext context) {
		Config config = context.config

		switch (config.multiTenant.scmProviderType) {
			case ScmProviderType.GITLAB:
				return new GitlabProvider(context, config.multiTenant.gitlab)
			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(context,
					config.multiTenant.scmManager,
					k8sClient,
					networkingUtils,
					centralScmManagerServicePrefix(config))

			default:
				throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.multiTenant.scmProviderType}")
		}
	}

	private String centralScmManagerServicePrefix(Config config) {
		def namespace = (config.multiTenant.scmManager.namespace ?: '').strip()
		def baseNamespace = 'scm-manager'

		if (namespace == baseNamespace || !namespace.endsWith(baseNamespace)) {
			return ''
		}

		return namespace.substring(0, namespace.length() - baseNamespace.length())
	}
}