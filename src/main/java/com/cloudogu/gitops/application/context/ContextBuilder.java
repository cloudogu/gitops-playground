package com.cloudogu.gitops.application.context;

import com.cloudogu.gitops.config.Config;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class ContextBuilder {

  private final Config config;

  public DeploymentContext build() {
    return new DeploymentContext(
        config,
        tenantMode(),
        scmManagerDeploymentMode(),
        config.getApplication().getMirrorRepos(),
        clusterDistribution());
  }

  private DeploymentContext.TenantMode tenantMode() {
    return config.getMultiTenant().getUseDedicatedInstance()
        ? DeploymentContext.TenantMode.MULTI_TENANT
        : DeploymentContext.TenantMode.SINGLE_TENANT;
  }

  private DeploymentContext.ScmManagerDeploymentMode scmManagerDeploymentMode() {
    boolean internal =
        config.getScm() != null
            && config.getScm().getScmManager() != null
            && config.getScm().getScmManager().getInternal();
    return internal
        ? DeploymentContext.ScmManagerDeploymentMode.INTERNAL
        : DeploymentContext.ScmManagerDeploymentMode.EXTERNAL;
  }

  private DeploymentContext.ClusterDistribution clusterDistribution() {
    return config.getApplication().getOpenshift()
        ? DeploymentContext.ClusterDistribution.OPENSHIFT
        : DeploymentContext.ClusterDistribution.KUBERNETES;
  }
}
