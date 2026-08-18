package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.application.context.DeploymentContext;
import jakarta.inject.Singleton;

@Singleton
public class JenkinsConfigUpdater {

	public void updateUrl(DeploymentContext context, String url) {
		context.getConfig().getJenkins().setUrl(url);
	}
}
