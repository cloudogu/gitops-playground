package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.scmmanager.Permission

interface GitProvider {
    /**
     * @param repoTarget "namespace/name"
     * @return true when repo new added, false when repo already exists
     */
    boolean createRepository(String repoTarget, String description, boolean initialize)

    String getUrl() // TODO Gitlab and ScmManager should impelent this:

    //TODO role should be a string, because gitlab and scmmanager have different permissions role.
    // In both provider we have to match the role or the role will cmome from config ??
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission)

    String computePushUrl(String repoTarget)

    Credentials getCredentials()

    //TODO implement
    void deleteRepository(String namespace, String repository, boolean prefixNamespace)

    //TODO implement
    void deleteUser(String name)

    //TODO implement
    void setDefaultBranch(String repoTarget, String branch)

}