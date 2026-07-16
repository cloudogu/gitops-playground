package com.cloudogu.gitops.application.context;

import com.cloudogu.gitops.config.Config;
import jakarta.inject.Singleton;

@Singleton
public class ContextBuilder {

    private final Config config;

    public ContextBuilder(Config config) {
        this.config = config;
    }

    public DeploymentContext build() {
        return new DeploymentContext(
            config,
            tenantMode(),
            scmManagerDeploymentMode(),
            Boolean.TRUE.equals(config.getApplication().getMirrorRepos()),
            clusterDistribution()
        );
    }

    private DeploymentContext.TenantMode tenantMode() {
        return Boolean.TRUE.equals(config.getMultiTenant().getUseDedicatedInstance()) ?
               DeploymentContext.TenantMode.MULTI_TENANT :
               DeploymentContext.TenantMode.SINGLE_TENANT;
    }

    private DeploymentContext.ScmManagerDeploymentMode scmManagerDeploymentMode() {
        boolean internal = config.getScm() != null &&
                           config.getScm().getScmManager() != null &&
                           Boolean.TRUE.equals(config.getScm().getScmManager().getInternal());
        return internal ?
               DeploymentContext.ScmManagerDeploymentMode.INTERNAL :
               DeploymentContext.ScmManagerDeploymentMode.EXTERNAL;
    }

    private DeploymentContext.ClusterDistribution clusterDistribution() {
        return Boolean.TRUE.equals(config.getApplication().getOpenshift()) ?
               DeploymentContext.ClusterDistribution.OPENSHIFT :
               DeploymentContext.ClusterDistribution.KUBERNETES;
    }
}
