package com.cloudogu.gitops.tools.core.argocd.mode;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoSetup;
import jakarta.inject.Singleton;

@Singleton
public class DeploymentModeFactory {

  public DeploymentMode create(
      DeploymentContext context,
      Config config,
      K8sClient k8sClient,
      GitHandler gitHandler,
      RepositoryWorkspace repositoryWorkspace,
      ArgoCDRepoSetup repoSetup,
      ArgoCDRepoLayout clusterResourcesRepo,
      String namespace) {

    if (context.isMultiTenant()) {
      return new DedicatedMultiTenantMode(
          config,
          k8sClient,
          gitHandler,
          repositoryWorkspace,
          repoSetup,
          clusterResourcesRepo,
          namespace);
    }

    return new SingleTenantMode(
        config, k8sClient, gitHandler, repositoryWorkspace, clusterResourcesRepo, namespace);
  }
}
