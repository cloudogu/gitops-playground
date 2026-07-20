package com.cloudogu.gitops.application.context;

import com.cloudogu.gitops.config.Config;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeploymentContext {

    private final Config config;
    private final TenantMode tenantMode;
    private final ScmManagerDeploymentMode scmManagerDeploymentMode;
    private final Boolean airgapped;
    private final ClusterDistribution clusterDistribution;

    public Config getConfig() {
        return config;
    }

    public TenantMode getTenantMode() {
        return tenantMode;
    }

    public ScmManagerDeploymentMode getScmManagerDeploymentMode() {
        return scmManagerDeploymentMode;
    }

    public Boolean getAirgapped() {
        return airgapped;
    }

    public ClusterDistribution getClusterDistribution() {
        return clusterDistribution;
    }

    public Boolean isMultiTenant() {
        return tenantMode == TenantMode.MULTI_TENANT;
    }

    public Boolean isSingleTenant() {
        return tenantMode == TenantMode.SINGLE_TENANT;
    }

    public Boolean isInternalScmManager() {
        return scmManagerDeploymentMode == ScmManagerDeploymentMode.INTERNAL;
    }

    public Boolean isExternalScmManager() {
        return scmManagerDeploymentMode == ScmManagerDeploymentMode.EXTERNAL;
    }

    public Boolean isAirgapped() {
        return airgapped;
    }

    public Boolean isOpenshift() {
        return clusterDistribution == ClusterDistribution.OPENSHIFT;
    }

    public enum TenantMode {
        SINGLE_TENANT,
        MULTI_TENANT
    }

    public enum ScmManagerDeploymentMode {
        INTERNAL,
        EXTERNAL
    }

    public enum ClusterDistribution {
        KUBERNETES,
        OPENSHIFT
    }
}
