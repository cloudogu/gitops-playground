package com.cloudogu.gitops.tools.core.argocd.mode;

public interface DeploymentMode {

    void createSCMCredentialsSecret();

    void generateRBAC();

    void updateManagedNamespaces();

    void applyBootstrapResources();
}
