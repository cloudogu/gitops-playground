package com.cloudogu.gitops.tools.core.argocd.mode;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoSetup;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDToolConfig;
import jakarta.inject.Singleton;

@Singleton
public class DeploymentModeFactory {

	public DeploymentMode create(
		ArgoCDToolConfig config,
		K8sClient k8sClient,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace,
		ArgoCDRepoSetup repoSetup,
		ArgoCDRepoLayout clusterResourcesRepo,
		String namespace) {

		if (config.multiTenant()) {
			return new DedicatedMultiTenantMode(
				config,
				k8sClient,
				gitHandler,
				repositoryWorkspace,
				repoSetup,
				clusterResourcesRepo,
				namespace
			);
		}

		return new SingleTenantMode(
			config,
			k8sClient,
			gitHandler,
			repositoryWorkspace,
			clusterResourcesRepo,
			namespace
		);
	}
}
