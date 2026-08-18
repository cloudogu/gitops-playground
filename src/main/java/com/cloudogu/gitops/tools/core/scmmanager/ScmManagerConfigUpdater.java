package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import jakarta.inject.Singleton;

@Singleton
public class ScmManagerConfigUpdater {

	public void updateNamespace(DeploymentContext context, String namespace) {
		context.getConfig().getScm().getScmManager().setNamespace(namespace);
	}
}
