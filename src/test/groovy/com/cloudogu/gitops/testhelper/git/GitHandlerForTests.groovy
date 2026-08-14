package com.cloudogu.gitops.testhelper.git

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.K8sClientForTest
import com.cloudogu.gitops.utils.NetworkingUtils

class GitHandlerForTests extends GitHandler {
	private final GitProvider tenantProvider
	private final GitProvider centralProvider

	GitHandlerForTests(GitProvider tenantProvider, GitProvider centralProvider = null) {
		super(new K8sClientForTest(), new NetworkingUtils(), new Config())
		this.tenantProvider = tenantProvider
		this.centralProvider = centralProvider
		this.tenant = tenantProvider
		this.central = centralProvider
	}

	@Override
	void prepareProviders(DeploymentContext context) {
		// Inject the test providers into the base class before running the real logic
		this.tenant = tenantProvider
		this.central = context.isMultiTenant() ? centralProvider : null

		// Mirror the production side effect: set namespace for internal SCMM
		if (context.config?.scm?.scmManager != null) {
			context.config.scm.scmManager.namespace = "${context.config.application.namePrefix}scm-manager".toString()
		}
	}

	@Override
	void validate() {}

}
