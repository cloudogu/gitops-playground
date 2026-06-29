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
			log.debug('Setting configs for internal SCM-Manager')

			config.scm.scmManager.internal = true
			config.scm.scmManager.namespace = prefixedNamespace(config.scm.scmManager.namespace)
			config.scm.scmManager.urlForJenkins =
				"http://scmm.${config.scm.scmManager.namespace}.svc.cluster.local/scm"
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

	private GitProvider createTenantScmProvider() {
		switch (config.scm.scmProviderType) {
			case ScmProviderType.GITLAB:
				return new GitlabProvider(config, config.scm.gitlab)
			case ScmProviderType.SCM_MANAGER:
				return new ScmManagerProvider(config,
					config.scm.scmManager,
					k8sClient,
					networkingUtils,
					config.application.namePrefix ?: '')

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
					networkingUtils,
					centralScmManagerServicePrefix())

			default:
				throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.multiTenant.scmProviderType}")
		}
	}

	private String prefixedNamespace(String namespace) {
		String prefix = config.application.namePrefix ?: ''
		String baseNamespace = namespace ?: 'scm-manager'

		if (prefix && baseNamespace.startsWith(prefix)) {
			return baseNamespace
		}

		return "${prefix}${baseNamespace}".toString()
	}


	private String centralScmManagerServicePrefix() {
		def namespace = (config.multiTenant.scmManager.namespace ?: '').strip()
		def baseNamespace = 'scm-manager'

		if (namespace == baseNamespace || !namespace.endsWith(baseNamespace)) {
			return ''
		}

		return namespace.substring(0, namespace.length() - baseNamespace.length())
	}
}