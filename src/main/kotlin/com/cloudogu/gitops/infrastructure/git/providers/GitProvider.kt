package com.cloudogu.gitops.infrastructure.git.providers

import com.cloudogu.gitops.config.Credentials
import java.net.URI

interface GitProvider {

    fun createRepository(repoTarget: String, description: String): Boolean {
        return createRepository(repoTarget, description, true)
    }

    fun createRepository(repoTarget: String, description: String, initialize: Boolean): Boolean

    fun setRepositoryPermission(repoTarget: String, principal: String, role: AccessRole, scope: Scope)

    fun repoUrl(repoTarget: String?): String {
        return repoUrl(repoTarget, RepoUrlScope.IN_CLUSTER)
    }

    fun repoUrl(repoTarget: String?, scope: RepoUrlScope): String

    fun repoPrefix(): String

    fun getCredentials(): Credentials?

    fun prometheusMetricsEndpoint(): URI?

    fun getUrl(): String?

    fun getProtocol(): String?

    fun getHost(): String?

    fun getGitOpsUsername(): String?
}

enum class AccessRole {
    READ, WRITE, MAINTAIN, ADMIN, OWNER
}

enum class Scope {
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
enum class RepoUrlScope {
    IN_CLUSTER,
    CLIENT
}
