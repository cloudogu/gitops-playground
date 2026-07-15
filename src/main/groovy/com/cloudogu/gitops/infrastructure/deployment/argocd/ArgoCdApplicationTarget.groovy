package com.cloudogu.gitops.infrastructure.deployment.argocd

/**
 * Describes where and how an ArgoCD Application manifest should be created.
 *
 * <p>The target contains values that depend on the current deployment mode, for example
 * single-tenant or dedicated multi-tenant. Keeping these values together avoids passing
 * loosely related strings through the deployment strategy.</p>*/
class ArgoCdApplicationTarget {

	final String applicationName
	final String namespace
	final String project
	final boolean createDestinationNamespace

	ArgoCdApplicationTarget(String applicationName,
		String namespace,
		String project,
		boolean createDestinationNamespace) {
		this.applicationName = applicationName
		this.namespace = namespace
		this.project = project
		this.createDestinationNamespace = createDestinationNamespace
	}
}