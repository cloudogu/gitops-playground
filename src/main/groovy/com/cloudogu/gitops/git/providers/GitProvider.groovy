package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials

interface GitProvider {
    /**
     * @param repoTarget "namespace/name"
     * @return true when repo new added, false when repo already exists
     */
    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission)

    String computePushUrl(String repoTarget)

    Credentials pushAuth(boolean isCentralRepo)

    //TODO implement
    void deleteRepository(String namespace, String repository, boolean prefixNamespace)

    //TODO implement
    void deleteUser(String name)

    //TODO implement
    void setDefaultBranch(String repoTarget, String branch)

}