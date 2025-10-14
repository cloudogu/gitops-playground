package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials

interface GitProvider {

    default boolean createRepository(String repoTarget, String description) {
        return createRepository(repoTarget, description, true);
    }

    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope)

    default String repoUrl(String repoTarget) {
        return repoUrl(repoTarget, RepoUrlScope.IN_CLUSTER);
    }

    String repoUrl(String repoTarget, RepoUrlScope scope);

    String repoPrefix(boolean includeNamePrefix)

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

/**
 * IN_CLUSTER: URLs intended for workloads running inside the Kubernetes cluster
 *             (e.g., ArgoCD, Jobs, in-cluster automation).
 *
 * CLIENT    : URLs intended for interactive or CI clients performing push/clone operations,
 *             regardless of their location.
 *             If the application itself runs inside Kubernetes, the Service DNS is used;
 *             otherwise, NodePort (for internal installations) or externalBase (for external ones)
 *             is selected automatically.
 */
enum RepoUrlScope {
    IN_CLUSTER,
    CLIENT
}