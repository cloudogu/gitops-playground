package com.cloudogu.gitops.gitabstraction


interface GitProvider {
    /**
     * @param repoTarget "namespace/name"
     * @return true wenn neu angelegt, false wenn bereits vorhanden
     */
    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setDefaultBranch(String repoTarget, String branch)

    void setRepositoryPermission(String repoTarget, String principal, String role, boolean groupPermission)

    String computePushUrl(String repoTarget)

}