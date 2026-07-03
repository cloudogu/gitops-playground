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
		return new DeploymentContext(
			config,
			tenantMode(),
			scmManagerDeploymentMode(),
			config.application.mirrorRepos == true,
			clusterDistribution()
		)
	}

	private DeploymentContext.TenantMode tenantMode() {
		return config.multiTenant.useDedicatedInstance ?
		       DeploymentContext.TenantMode.MULTI_TENANT :
		       DeploymentContext.TenantMode.SINGLE_TENANT
	}

	private DeploymentContext.ScmManagerDeploymentMode scmManagerDeploymentMode() {
		return config.scm.scmManager?.internal ?
		       DeploymentContext.ScmManagerDeploymentMode.INTERNAL :
		       DeploymentContext.ScmManagerDeploymentMode.EXTERNAL
	}

	private DeploymentContext.ClusterDistribution clusterDistribution() {
		return config.application.openshift ?
		       DeploymentContext.ClusterDistribution.OPENSHIFT :
		       DeploymentContext.ClusterDistribution.KUBERNETES
	}
}