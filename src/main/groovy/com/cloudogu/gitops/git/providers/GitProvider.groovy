package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.scmmanager.Permission

interface GitProvider {

    boolean createRepository(String repoTarget, String description, boolean initialize)



    String getUrl() // TODO Gitlab and ScmManager should implement this:

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