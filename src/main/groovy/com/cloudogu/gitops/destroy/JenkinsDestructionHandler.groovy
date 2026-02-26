package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JobManager

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton

@Singleton
@Order(300)
class JenkinsDestructionHandler implements DestructionHandler {
	private JobManager jobManager
	private GlobalPropertyManager globalPropertyManager
	private Config configuration

	JenkinsDestructionHandler(JobManager jobManager, Config configuration, GlobalPropertyManager globalPropertyManager) {
		this.jobManager = jobManager
		this.configuration = configuration
		this.globalPropertyManager = globalPropertyManager
	}

	@Override
	void destroy() {
		jobManager.deleteJob("${configuration.application.namePrefix}example-apps")
		globalPropertyManager.deleteGlobalProperty("SCMM_URL")
		globalPropertyManager.deleteGlobalProperty("${configuration.application.namePrefixForEnvVars}REGISTRY_URL")
		globalPropertyManager.deleteGlobalProperty("${configuration.application.namePrefixForEnvVars}REGISTRY_PATH")
		globalPropertyManager.deleteGlobalProperty("${configuration.application.namePrefixForEnvVars}REGISTRY_PROXY_URL")
		globalPropertyManager.deleteGlobalProperty("${configuration.application.namePrefixForEnvVars}K8S_VERSION")
	}
}