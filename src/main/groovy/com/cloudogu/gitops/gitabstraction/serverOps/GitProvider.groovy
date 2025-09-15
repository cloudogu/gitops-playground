package com.cloudogu.gitops.gitabstraction.serverOps

import com.cloudogu.gitops.scmm.api.Permission


interface GitProvider {
    /**
     * @param repoTarget "namespace/name"
     * @return true when repo new added, false when repo already exists
     */
    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission)

    String computePushUrl(String repoTarget)

}