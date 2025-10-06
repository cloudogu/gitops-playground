package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials

interface GitProvider {

    default boolean createRepository(String repoTarget, String description) {
        return createRepository(repoTarget, description, true);
    }

    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope)

    String computePushUrl(String repoTarget)

    String computePullUrlForInCluster(String repoTarget)

    String computeRepoPrefixForInCluster(boolean includeNamePrefix)

    Credentials getCredentials()

    URI prometheusMetricsEndpoint()

    //TODO implement
    void deleteRepository(String namespace, String repository, boolean prefixNamespace)

    //TODO implement
    void deleteUser(String name)

    //TODO implement
    void setDefaultBranch(String repoTarget, String branch)

    String getUrl()

    String getProtocol()

    String getHost() //TODO? can we maybe get this via helper and config?

    String getGitOpsUsername()

}

enum AccessRole {
    READ, WRITE, MAINTAIN, ADMIN, OWNER
}

enum Scope {
    USER, GROUP
}