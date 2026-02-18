package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Credentials

interface GitProvider {

    default boolean createRepository(String repoTarget, String description) {
        return createRepository(repoTarget, description, true)
    }

    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope)

    default String repoUrl(String repoTarget) {
        return repoUrl(repoTarget, RepoUrlScope.IN_CLUSTER)
    }

    String repoUrl(String repoTarget, RepoUrlScope scope)

    String repoPrefix()

    Credentials getCredentials()

    URI prometheusMetricsEndpoint()

    /**
     * Deletes the given repository on the provider, if supported.
     * Note: This capability is not used by the current destruction flow,
     * which talks directly to provider-specific clients (e.g. ScmManagerApiClient).*/
    void deleteRepository(String namespace, String repository, boolean prefixNamespace)

    /**
     * Deletes a user account on the provider, if supported.
     * Note: Not used by the current destruction flow; kept as an optional capability
     * on the GitProvider abstraction */
    void deleteUser(String name)

    /**
     * Sets the default branch of a repository, if supported by the provider;
     * kept as an optional capability on the GitProvider abstraction */
    void setDefaultBranch(String repoTarget, String branch)

    String getUrl()

    String getProtocol()

    String getHost()

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