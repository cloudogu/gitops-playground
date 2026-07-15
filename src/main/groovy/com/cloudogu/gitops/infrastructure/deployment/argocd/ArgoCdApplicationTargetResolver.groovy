package com.cloudogu.gitops.infrastructure.deployment.argocd

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config

import jakarta.inject.Singleton

@Singleton
class ArgoCdApplicationTargetResolver {

	ArgoCdApplicationTarget resolve(DeploymentContext context, String repoName) {
		Config config = context.config

		String namePrefix = config.application.namePrefix ?: ''
		String prefix = namePrefix.strip()

		String applicationName = prefix ? "${prefix}${repoName}" : repoName
		String namespace = "${namePrefix}${config.features.argocd.namespace}"
		String project = 'cluster-resources'

		boolean isOperatorMode = config.features.argocd.operator as boolean
		boolean createDestinationNamespace = !isOperatorMode

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