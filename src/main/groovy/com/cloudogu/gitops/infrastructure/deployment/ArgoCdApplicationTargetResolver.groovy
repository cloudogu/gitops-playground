package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config

import jakarta.inject.Singleton

/**
 * Resolves the ArgoCD Application target for the current deployment context.
 *
 * <p>This keeps deployment-mode specific decisions out of {@link ArgoCdApplicationStrategy}.
 * The strategy only writes the Application manifest, while this resolver decides in which
 * ArgoCD namespace and project the Application belongs.</p>*/
@Singleton
class ArgoCdApplicationTargetResolver {

	private final DeploymentContext context

	ArgoCdApplicationTargetResolver(DeploymentContext context) {
		this.context = context
	}

	ArgoCdApplicationTarget resolve(String repoName) {
		Config config = context.config

		String namePrefix = config.application.namePrefix ?: ''
		String prefix = namePrefix.strip()

		String applicationName = prefix ? "${prefix}${repoName}" : repoName
		String namespace = "${namePrefix}${config.features.argocd.namespace}"
		String project = 'cluster-resources'

		boolean isOperatorMode = config.features.argocd.operator as boolean
		// Namespaces are created by ArgoCD only when ArgoCD is installed without the operator.
		boolean createDestinationNamespace = isOperatorMode ? false : true

		if (context.isMultiTenant()) {
			namespace = config.multiTenant.centralArgocdNamespace as String
			project = prefix.replaceFirst(/-$/, '')
		}

		return new ArgoCdApplicationTarget(applicationName,
			namespace,
			project,
			createDestinationNamespace)
	}
}