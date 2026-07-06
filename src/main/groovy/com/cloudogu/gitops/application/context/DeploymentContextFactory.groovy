package com.cloudogu.gitops.application.context

import io.micronaut.context.annotation.Factory

import jakarta.inject.Singleton

@Factory
class DeploymentContextFactory {

	@Singleton
	DeploymentContext deploymentContext(ContextBuilder contextBuilder) {
		return contextBuilder.build()
	}
}