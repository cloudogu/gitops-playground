package com.cloudogu.gitops.features.git

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.gitlab.Gitlab
import com.cloudogu.gitops.git.providers.scmmanager.ScmManager
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(60)
class GitHandler extends Feature {

	Config config

	NetworkingUtils networkingUtils
	HelmStrategy helmStrategy
	FileSystemUtils fileSystemUtils
	K8sClient k8sClient

	GitProvider tenant
	GitProvider central

	GitHandler(Config config, HelmStrategy helmStrategy, FileSystemUtils fileSystemUtils, K8sClient k8sClient, NetworkingUtils networkingUtils) {
		this.config = config
		this.helmStrategy = helmStrategy
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils
	}

	@Override
	boolean isEnabled() {
		return true
	}

	void validate() {
		if (config.scm.scmManager.url) {
			config.scm.scmManager.internal = false
			config.scm.scmManager.urlForJenkins = config.scm.scmManager.url
		} else {
			log.debug("Setting configs for internal SCM-Manager")
			// We use the K8s service as default name here, because it is the only option:
			// "scmm.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9091)
			// will not work on Windows and MacOS.
			config.scm.scmManager.urlForJenkins = "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm"

			// More internal fields are set lazily in ScmManger.groovy (after SCMM is deployed and ports are known)
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

	//Retrieves the appropriate SCM for cluster resources depending on whether the environment is multi-tenant or not.
	GitProvider getResourcesScm() {
		if (central) {
			return central
		} else if (tenant) {
			return tenant
		} else {
			throw new IllegalStateException("No SCM provider found.")
		}
	}

	@Override
	void enable() {
		//TenantSCM
		switch (config.scm.scmProviderType) {
			case ScmProviderType.GITLAB:
				this.tenant = new Gitlab(this.config, this.config.scm.gitlab)
				break
			case ScmProviderType.SCM_MANAGER:
				def prefixedNamespace = "${config.application.namePrefix}scm-manager".toString()
				config.scm.scmManager.namespace = prefixedNamespace
				this.tenant = new ScmManager(this.config, config.scm.scmManager, helmStrategy, k8sClient, networkingUtils, true)
				// this.tenant.setup() setup will be here in future
				break
			default:
				throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM")
		}

		if (config.multiTenant.useDedicatedInstance) {
			switch (config.multiTenant.scmProviderType) {
				case ScmProviderType.GITLAB:
					this.central = new Gitlab(this.config, this.config.multiTenant.gitlab)
					break
				case ScmProviderType.SCM_MANAGER:
					this.central = new ScmManager(this.config, config.multiTenant.scmManager, helmStrategy, k8sClient, networkingUtils)
					break
				default:
					throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.scm.scmProviderType}")
			}
		}

		//can be removed if we combine argocd and cluster-resources
		final String namePrefix = (config?.application?.namePrefix ?: "").trim()
		if (this.central) {
			setupRepos(this.central, namePrefix)
			setupRepos(this.tenant, namePrefix)
		} else {
			setupRepos(this.tenant, namePrefix)
		}
	}

	static void setupRepos(GitProvider gitProvider, String namePrefix = "") {
		gitProvider.createRepository(withOrgPrefix(namePrefix, "argocd/cluster-resources"),
			"GitOps repo for basic cluster-resources")
	}

	/**
	 * Adds a prefix to the group/namespace part (before the first '/'):
	 * Example: "argocd/argocd" + "foo-" => "foo-argocd/argocd"*/
	static String withOrgPrefix(String prefix, String repoPath) {
		if (!prefix) return repoPath
		return prefix + repoPath
	}
}