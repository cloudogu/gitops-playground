package com.cloudogu.gitops.features.argocd

class ArgoCDRepoContext {
    RepoInitializationAction clusterResources
    RepoInitializationAction tenantBootstrap

    final List<RepoInitializationAction> allRepos = []

    void addClusterResources(RepoInitializationAction action) {
        this.clusterResources = action
        this.allRepos.add(action)
    }

    void addTenantBootstrap(RepoInitializationAction action) {
        this.tenantBootstrap = action
        this.allRepos.add(action)
    }

    boolean hasTenantBootstrap() {
        tenantBootstrap != null
    }
}
