package com.cloudogu.gitops.testhelper.git

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.K8sClientForTest
import com.cloudogu.gitops.utils.NetworkingUtils

class GitHandlerForTests extends GitHandler {
	private final GitProvider tenantProvider
	private final GitProvider centralProvider

	GitHandlerForTests(Config config, GitProvider tenantProvider, GitProvider centralProvider = null) {
		super(new ContextBuilder(config).build(), new K8sClientForTest(), new NetworkingUtils())
		this.tenantProvider = tenantProvider
		this.centralProvider = centralProvider
	}

	@Override
	void prepareProviders() {
		// Inject the test providers into the base class before running the real logic
		this.tenant = tenantProvider
		this.central = centralProvider

		// Mirror the production side effect: set namespace for internal SCMM
		if (this.config?.scm?.scmManager != null) {
			this.config.scm.scmManager.namespace = "${config.application.namePrefix}scm-manager".toString()
		}

		// === Run ONLY the repo setup logic (NO provider construction here) ===
		final String namePrefix = (config?.application?.namePrefix ?: "").trim()
		if (this.central) {
			setupRepos(this.central, namePrefix)
			setupRepos(this.tenant, namePrefix)
		} else {
			setupRepos(this.tenant, namePrefix)
		}

	}

	@Override
	void validate() {}

	@Override
	GitProvider getTenant() {
		return tenantProvider
	}

	@Override
	GitProvider getCentral() {
		return centralProvider
	}

	@Override
	GitProvider getResourcesScm() {
		return centralProvider ?: tenantProvider
	}

}