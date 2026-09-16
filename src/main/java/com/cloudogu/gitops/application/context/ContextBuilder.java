package com.cloudogu.gitops.application.context;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class ContextBuilder {

	private final Config config;

	public DeploymentContext build() {
		return new DeploymentContext(
			tenantMode(),
			scmManagerDeploymentMode(),
			config.getApplication().getMirrorRepos(),
			clusterDistribution()
		);
	}

	private DeploymentContext.TenantMode tenantMode() {
		return config.getMultiTenant()
					 .getUseDedicatedInstance() ? DeploymentContext.TenantMode.MULTI_TENANT : DeploymentContext.TenantMode.SINGLE_TENANT;
	}

	private DeploymentContext.ScmManagerDeploymentMode scmManagerDeploymentMode() {
		if (config.getScm() == null || config.getScm().getScmProviderType() != ScmProviderType.SCM_MANAGER) {
			return DeploymentContext.ScmManagerDeploymentMode.DISABLED;
		}

		boolean internal = config.getScm().getScmManager() != null
			&& Boolean.TRUE.equals(config.getScm().getScmManager().getInternal());

		return internal
			? DeploymentContext.ScmManagerDeploymentMode.INTERNAL
			: DeploymentContext.ScmManagerDeploymentMode.EXTERNAL;
	}

	private DeploymentContext.ClusterDistribution clusterDistribution() {
		return config.getApplication()
					 .getOpenshift() ? DeploymentContext.ClusterDistribution.OPENSHIFT : DeploymentContext.ClusterDistribution.KUBERNETES;
	}
}
