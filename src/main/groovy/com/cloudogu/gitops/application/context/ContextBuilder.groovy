package com.cloudogu.gitops.application.context

import com.cloudogu.gitops.config.Config

import jakarta.inject.Singleton

@Singleton
class ContextBuilder {

	private final Config config

	ContextBuilder(Config config) {
		this.config = config
	}

	DeploymentContext build() {
		return new DeploymentContext(config,
			config.multiTenant.useDedicatedInstance ? DeploymentContext.TenantMode.MULTI_TENANT : DeploymentContext.TenantMode.SINGLE_TENANT,
			config.scm.scmManager?.internal ? DeploymentContext.ScmManagerMode.INTERNAL : DeploymentContext.ScmManagerMode.EXTERNAL,
			config.features.certManager.active,
			config.application.mirrorRepos,
			config.multiTenant.scmProviderType,
			config.scm.scmProviderType,
			config.application.openshift ? DeploymentContext.Platform.OPENSHIFT : DeploymentContext.Platform.KUBERNETES)
	}
}