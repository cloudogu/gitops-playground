package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager;
import com.cloudogu.gitops.infrastructure.jenkins.JobManager;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@Order(300)
@RequiredArgsConstructor
public class JenkinsDestructionHandler implements DestructionHandler {

	private final JobManager jobManager;
	private final Config config;
	private final GlobalPropertyManager globalPropertyManager;

	@Override
	public void destroy() {
		String namePrefixForEnvVars = config.getApplication().getNamePrefixForEnvVars();

		jobManager.deleteJob(config.getApplication().getNamePrefix() + "example-apps");
		globalPropertyManager.deleteGlobalProperty("SCMM_URL");
		globalPropertyManager.deleteGlobalProperty(namePrefixForEnvVars + "REGISTRY_URL");
		globalPropertyManager.deleteGlobalProperty(namePrefixForEnvVars + "REGISTRY_PATH");
		globalPropertyManager.deleteGlobalProperty(namePrefixForEnvVars + "REGISTRY_PROXY_URL");
		globalPropertyManager.deleteGlobalProperty(namePrefixForEnvVars + "REGISTRY_PROXY_PATH");

		globalPropertyManager.deleteGlobalProperty(namePrefixForEnvVars + "K8S_VERSION");
	}
}
