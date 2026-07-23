package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.gitlab.GitlabProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class GitHandler {

  @Getter private final K8sClient k8sClient;
  @Getter private final NetworkingUtils networkingUtils;

  @Getter @Setter private GitProvider tenant;

  @Getter @Setter private GitProvider central;

  public void validate(DeploymentContext context) {
    Config config = context.getConfig();

    boolean gitlabRequested = config.getScm().getScmProviderType() == ScmProviderType.GITLAB;
    boolean gitlabUrlConfigured =
        config.getScm().getGitlab() != null
            && !StringUtils.isEmpty(config.getScm().getGitlab().getUrl());
    if (gitlabRequested || gitlabUrlConfigured) {
      config.getScm().setScmProviderType(ScmProviderType.GITLAB);
      config.getScm().setScmManager(null);

      if (config.getScm().getGitlab() == null
          || StringUtils.isEmpty(config.getScm().getGitlab().getUrl())
          || StringUtils.isEmpty(config.getScm().getGitlab().getPassword())
          || StringUtils.isEmpty(config.getScm().getGitlab().getParentGroupId())) {
        throw new IllegalArgumentException(
            "GitLab configuration incomplete: please provide url, password (PAT) and parentGroupId");
      }
      return;
    }

    config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
    if (config.getScm().getScmManager() != null) {
      String prefix = config.getApplication().getNamePrefix();
      if (prefix == null) {
        prefix = "";
      }
      config.getScm().getScmManager().setGitOpsUsername(prefix + "gitops");
    }
  }

  public void prepareProviders(DeploymentContext context) {
    this.tenant = createTenantScmProvider(context);

    if (context.isMultiTenant()) {
      this.central = createCentralScmProvider(context);
    }
  }

  public GitProvider getResourcesScm() {
    if (central != null) {
      return central;
    }

    if (tenant != null) {
      return tenant;
    }

    throw new IllegalStateException("No SCM provider found.");
  }

  private GitProvider createTenantScmProvider(DeploymentContext context) {
    Config config = context.getConfig();

    return switch (config.getScm().getScmProviderType()) {
      case GITLAB -> new GitlabProvider(context, config.getScm().getGitlab());
      case SCM_MANAGER -> {
        String prefix = config.getApplication().getNamePrefix();
        if (prefix == null) {
          prefix = "";
        }
        yield new ScmManagerProvider(
            context, config.getScm().getScmManager(), k8sClient, networkingUtils, prefix);
      }
      default ->
          throw new IllegalArgumentException(
              "Unsupported SCM provider found in TenantSCM: "
                  + config.getScm().getScmProviderType());
    };
  }

  private GitProvider createCentralScmProvider(DeploymentContext context) {
    Config config = context.getConfig();

    return switch (config.getMultiTenant().getScmProviderType()) {
      case GITLAB -> new GitlabProvider(context, config.getMultiTenant().getGitlab());
      case SCM_MANAGER ->
          new ScmManagerProvider(
              context,
              config.getMultiTenant().getScmManager(),
              k8sClient,
              networkingUtils,
              centralScmManagerServicePrefix(config));
      default ->
          throw new IllegalArgumentException(
              "Unsupported SCM-Central provider: " + config.getMultiTenant().getScmProviderType());
    };
  }

  private static String centralScmManagerServicePrefix(Config config) {
    String namespace = config.getMultiTenant().getScmManager().getNamespace();
    if (namespace == null) {
      namespace = "";
    }
    namespace = namespace.strip();
    String baseNamespace = "scm-manager";

    if (namespace.equals(baseNamespace) || !namespace.endsWith(baseNamespace)) {
      return "";
    }

    return namespace.substring(0, namespace.length() - baseNamespace.length());
  }
}
