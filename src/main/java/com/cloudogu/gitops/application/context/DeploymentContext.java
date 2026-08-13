package com.cloudogu.gitops.application.context;

import com.cloudogu.gitops.config.Config;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DeploymentContext {

	private final Config config;
	private final TenantMode tenantMode;
	private final ScmManagerDeploymentMode scmManagerDeploymentMode;
	private final boolean airgapped;
	private final ClusterDistribution clusterDistribution;

	public boolean isMultiTenant() {
		return tenantMode == TenantMode.MULTI_TENANT;
	}

	public boolean isSingleTenant() {
		return tenantMode == TenantMode.SINGLE_TENANT;
	}

	public boolean isInternalScmManager() {
		return scmManagerDeploymentMode == ScmManagerDeploymentMode.INTERNAL;
	}

	public boolean isExternalScmManager() {
		return scmManagerDeploymentMode == ScmManagerDeploymentMode.EXTERNAL;
	}

	public boolean isAirgapped() {
		return airgapped;
	}

	public boolean isOpenshift() {
		return clusterDistribution == ClusterDistribution.OPENSHIFT;
	}

	public enum TenantMode {
		SINGLE_TENANT,
		MULTI_TENANT
	}

	public enum ScmManagerDeploymentMode {
		INTERNAL,
		EXTERNAL,
		DISABLED
	}

	public enum ClusterDistribution {
		KUBERNETES,
		OPENSHIFT
	}
}
