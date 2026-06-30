package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.infrastructure.jenkins.JobManager

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton

@Singleton
@Order(300)
class JenkinsDestructionHandler implements DestructionHandler {
	private JobManager jobManager
	private GlobalPropertyManager globalPropertyManager
	private DeploymentContext context

	JenkinsDestructionHandler(JobManager jobManager, DeploymentContext context, GlobalPropertyManager globalPropertyManager) {
		this.jobManager = jobManager
		this.context = context
		this.globalPropertyManager = globalPropertyManager
	}

	@Override
	void destroy() {
		def config = context.config
		jobManager.deleteJob("${config.application.namePrefix}example-apps")
		globalPropertyManager.deleteGlobalProperty("SCMM_URL")
		globalPropertyManager.deleteGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_URL")
		globalPropertyManager.deleteGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PATH")
		globalPropertyManager.deleteGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PROXY_URL")
		globalPropertyManager.deleteGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PROXY_PATH")

		globalPropertyManager.deleteGlobalProperty("${config.application.namePrefixForEnvVars}K8S_VERSION")
	}
}