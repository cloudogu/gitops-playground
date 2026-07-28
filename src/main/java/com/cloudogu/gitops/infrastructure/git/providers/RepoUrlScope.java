package com.cloudogu.gitops.infrastructure.git.providers;

/**
 * IN_CLUSTER: URLs intended for workloads running inside the Kubernetes cluster (e.g., ArgoCD,
 * Jobs, in-cluster automation).
 *
 * <p>CLIENT : URLs intended for interactive or CI clients performing push/clone operations,
 * regardless of their location. If the application itself runs inside Kubernetes, the Service DNS
 * is used; otherwise, NodePort (for internal installations) or externalBase (for external ones) is
 * selected automatically.
 */
public enum RepoUrlScope {
IN_CLUSTER,
CLIENT
}
