package com.cloudogu.gitops.application.context

import com.cloudogu.gitops.config.Config

class DeploymentContext {

	final Config config
	final TenantMode tenantMode
	final DeploymentMode scmManagerDeploymentMode
	final Boolean airgapped
	final ClusterDistribution clusterDistribution

	DeploymentContext(Config config,
		TenantMode tenantMode,
		DeploymentMode scmManagerDeploymentMode,
		Boolean airgapped,
		ClusterDistribution clusterDistribution) {
		this.config = config
		this.tenantMode = tenantMode
		this.scmManagerDeploymentMode = scmManagerDeploymentMode
		this.airgapped = airgapped
		this.clusterDistribution = clusterDistribution
	}

	Boolean isMultiTenant() {
		return tenantMode == TenantMode.MULTI_TENANT
	}

	Boolean isSingleTenant() {
		return tenantMode == TenantMode.SINGLE_TENANT
	}

	Boolean isInternalScmManager() {
		return scmManagerDeploymentMode == DeploymentMode.INTERNAL
	}

	Boolean isExternalScmManager() {
		return scmManagerDeploymentMode == DeploymentMode.EXTERNAL
	}

	Boolean isNonAirgapped() {
		return !airgapped
	}

	Boolean isOpenshift() {
		return clusterDistribution == ClusterDistribution.OPENSHIFT
	}

	enum TenantMode {
		SINGLE_TENANT,
		MULTI_TENANT
	}

	enum DeploymentMode {
		INTERNAL,
		EXTERNAL
	}

	enum ClusterDistribution {
		KUBERNETES,
		OPENSHIFT
	}
}
