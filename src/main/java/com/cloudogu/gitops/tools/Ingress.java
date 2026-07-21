package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.tools.common.Tool;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Order(150)
@Slf4j
public class Ingress extends Tool {

  public static final String HELM_VALUES_PATH =
      "argocd/cluster-resources/apps/traefik/templates/values.ftl.yaml";

  private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
  private static final String TOOL_NAME = "traefik";
  private static final String RELEASE_NAME = "traefik";
  private static final String INGRESS_APP_PATH = "apps/traefik";

  private final ImagePullSecretCreator imagePullSecretCreator;
  @Getter @Setter private String namespace;

  public Ingress(
      FileSystemUtils fileSystemUtils,
      Deployer deployer,
      AirGappedUtils airGappedUtils,
      GitHandler gitHandler,
      ImagePullSecretCreator imagePullSecretCreator) {
    this.deployer = deployer;
    this.fileSystemUtils = fileSystemUtils;
    this.airGappedUtils = airGappedUtils;
    this.gitHandler = gitHandler;
    this.imagePullSecretCreator = imagePullSecretCreator;
  }

  @Override
  public boolean isEnabled(DeploymentContext context) {
    return Boolean.TRUE.equals(context.getConfig().getFeatures().getIngress().getActive());
  }

  @Override
  protected void preDeploy() {
    this.namespace = activeNamespace(context);

    createImagePullSecret();
    prepareIngressApp(repositoryWorkspace.getClusterResourcesRepository());
  }

  @Override
  protected void deploy() {
    Config.IngressSchema.IngressHelmSchema helmConfig =
        context.getConfig().getFeatures().getIngress().getHelm();

    deployHelmChart(TOOL_NAME, RELEASE_NAME, namespace, helmConfig, HELM_VALUES_PATH, context);
  }

  @Override
  protected void publishChanges() {
    publishClusterResourcesChanges(TOOL_NAME);
  }

  @Override
  protected String activeNamespace(DeploymentContext context) {
    return context.getConfig().getApplication().getNamePrefix()
        + context.getConfig().getFeatures().getIngress().getIngressNamespace();
  }

  private void createImagePullSecret() {
    imagePullSecretCreator.createIfRequired(context.getConfig(), namespace);
  }

  private void prepareIngressApp(GitRepo clusterResourcesRepo) {
    log.debug("Preparing ingress repository content in {}", clusterResourcesRepo.getRepoTarget());

    clusterResourcesRepo.copyDirectoryContents(
        CLUSTER_RESOURCES_SOURCE_DIR,
        ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, INGRESS_APP_PATH));
  }
}
