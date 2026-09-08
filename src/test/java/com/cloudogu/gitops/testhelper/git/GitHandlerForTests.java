package com.cloudogu.gitops.testhelper.git;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.utils.K8sClientForTest;
import com.cloudogu.gitops.utils.NetworkingUtils;

public class GitHandlerForTests extends GitHandler {

	private final GitProvider tenantProvider;
	private final GitProvider centralProvider;

	public GitHandlerForTests(GitProvider tenantProvider) {
		this(tenantProvider, null);
	}

	public GitHandlerForTests(GitProvider tenantProvider, GitProvider centralProvider) {
		super(new K8sClientForTest(), new NetworkingUtils(), new Config());
		this.tenantProvider = tenantProvider;
		this.centralProvider = centralProvider;
		setTenant(tenantProvider);
		setCentral(centralProvider);
	}

	@Override
	public void prepareProviders(DeploymentContext context) {
		// Inject the test providers into the base class before running the real logic
		setTenant(tenantProvider);
		setCentral(context.isMultiTenant() ? centralProvider : null);
	}

	@Override
	public void validate() {
	}
}
