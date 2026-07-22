package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.tools.common.Tool;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import com.cloudogu.gitops.utils.Tuple;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Order(300)
@Slf4j
public class Monitoring extends Tool {

  public static final String HELM_VALUES_PATH =
      "argocd/cluster-resources/apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml";
  public static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE =
      "argocd/cluster-resources/apps/monitoring/templates/rbac/namespace-isolation-rbac.ftl.yaml";
  public static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE =
      "argocd/cluster-resources/apps/monitoring/templates/netpols/prometheus-allow-scraping.ftl.yaml";

  private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
  private static final String TOOL_NAME = "monitoring";
  private static final String RELEASE_NAME = "kube-prometheus-stack";
  private static final String MONITORING_APP_PATH = "apps/monitoring";
  private static final String PASSWORD_KEY = "password";
  private static final String GENERIC_SECRET_TYPE = "generic";
  private static final String NAMESPACE_KEY = "namespace";
  private static final String MONITORING_RBAC_PATH = MONITORING_APP_PATH + "/misc/rbac";
  private static final String MONITORING_NETPOLS_PATH = MONITORING_APP_PATH + "/misc/netpols";
  private static final String MONITORING_DASHBOARD_PATH = MONITORING_APP_PATH + "/misc/dashboard";

  private final ImagePullSecretCreator imagePullSecretCreator;
  private final K8sClient k8sClient;
  @Getter @Setter private String namespace;

  public Monitoring(
      FileSystemUtils fileSystemUtils,
      Deployer deployer,
      K8sClient k8sClient,
      AirGappedUtils airGappedUtils,
      GitHandler gitHandler,
      ImagePullSecretCreator imagePullSecretCreator) {
    this.deployer = deployer;
    this.fileSystemUtils = fileSystemUtils;
    this.k8sClient = k8sClient;
    this.airGappedUtils = airGappedUtils;
    this.gitHandler = gitHandler;
    this.imagePullSecretCreator = imagePullSecretCreator;
  }

  @Override
  public boolean isEnabled(DeploymentContext context) {
    return context.getConfig().getFeatures().getMonitoring().getActive();
  }

  @Override
  protected void preDeploy() {
    this.namespace = activeNamespace(context);

    createImagePullSecret();
    prepareMonitoringHelmValues();

    // Create secrets imperatively here instead of values.yaml,
    // because we don't want credentials to be visible in the Git repo.
    setupMonitoringSecrets();
    createMonitoringCrd();

    prepareMonitoringApp(repositoryWorkspace.getClusterResourcesRepository());
    replaceMonitoringTemplates(repositoryWorkspace.getClusterResourcesRepository());
    writeMonitoringGitOpsArtifacts(repositoryWorkspace.getClusterResourcesRepository());
  }

  @Override
  protected void deploy() {
    deployHelmChart(
        TOOL_NAME,
        RELEASE_NAME,
        namespace,
        getConfig().getFeatures().getMonitoring().getHelm(),
        HELM_VALUES_PATH,
        context);
  }

  @Override
  protected void publishChanges() {
    // We always assume internal monitoring for deploying artifacts
    publishClusterResourcesChanges(TOOL_NAME);
  }

  @Override
  protected String activeNamespace(DeploymentContext context) {
    return context.getConfig().getApplication().getNamePrefix()
        + context.getConfig().getFeatures().getMonitoring().getNamespace();
  }

  private void createImagePullSecret() {
    imagePullSecretCreator.createIfRequired(getConfig(), namespace);
  }

  private void prepareMonitoringHelmValues() {
    String uid = "";
    if (context.isOpenshift()) {
      uid = findValidOpenShiftUid();
    }

    String grafanaUrl = getConfig().getFeatures().getMonitoring().getGrafanaUrl();
    String host = "";
    try {
      if (grafanaUrl != null && !grafanaUrl.isEmpty()) {
        host = new URL(grafanaUrl).getHost();
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse Grafana URL: " + grafanaUrl, e);
    }

    addHelmValuesData(TOOL_NAME, Map.of("grafana", Map.of("host", host)));
    addHelmValuesData(
        "namespaces",
        getConfig().getApplication().getNamespaces().getActiveNamespaces() != null
            ? getConfig().getApplication().getNamespaces().getActiveNamespaces()
            : Collections.emptySet());
    addHelmValuesData("scm", scmConfigurationMetrics());
    addHelmValuesData("jenkins", jenkinsConfigurationMetrics());
    addHelmValuesData("uid", uid);
  }

  private void prepareMonitoringApp(GitRepo clusterResourcesRepo) {
    log.debug(
        "Preparing Monitoring repository content in {}", clusterResourcesRepo.getRepoTarget());

    clusterResourcesRepo.copyDirectoryContents(
        CLUSTER_RESOURCES_SOURCE_DIR,
        ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, MONITORING_APP_PATH));
  }

  private void replaceMonitoringTemplates(GitRepo clusterResourcesRepo) {
    clusterResourcesRepo.replaceTemplates(Map.of("config", getConfig()));
  }

  private void writeMonitoringGitOpsArtifacts(GitRepo clusterResourcesRepo) {
    if (getConfig().getApplication().getNamespaceIsolation()) {
      generateNamespaceIsolationRBAC(clusterResourcesRepo);
    }

    if (getConfig().getApplication().getNetpols()) {
      generateNetpols(clusterResourcesRepo);
    }

    // Remove dashboards for features that are not enabled
    cleanupUnusedDashboards(clusterResourcesRepo);
  }

  private void setupMonitoringSecrets() {
    k8sClient.createSecret(
        GENERIC_SECRET_TYPE,
        "prometheus-metrics-creds-scmm",
        namespace,
        new Tuple<>(PASSWORD_KEY, getConfig().getApplication().getPassword()));

    k8sClient.createSecret(
        GENERIC_SECRET_TYPE,
        "prometheus-metrics-creds-jenkins",
        namespace,
        new Tuple<>(PASSWORD_KEY, getConfig().getJenkins().getMetricsPassword()));

    if ((getConfig().getFeatures().getMail().getSmtpUser() != null
            && !getConfig().getFeatures().getMail().getSmtpUser().isEmpty())
        || (getConfig().getFeatures().getMail().getSmtpPassword() != null
            && !getConfig().getFeatures().getMail().getSmtpPassword().isEmpty())) {
      k8sClient.createSecret(
          GENERIC_SECRET_TYPE,
          "grafana-email-secret",
          namespace,
          new Tuple<>("user", getConfig().getFeatures().getMail().getSmtpUser()),
          new Tuple<>(PASSWORD_KEY, getConfig().getFeatures().getMail().getSmtpPassword()));
    }
  }

  private void generateNamespaceIsolationRBAC(GitRepo clusterResourcesRepo) {
    for (String currentNamespace :
        getConfig().getApplication().getNamespaces().getActiveNamespaces()) {
      try {
        String rbacYaml =
            new TemplatingEngine()
                .template(
                    new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
                    Map.of(
                        NAMESPACE_KEY,
                        currentNamespace,
                        "namePrefix",
                        getConfig().getApplication().getNamePrefix(),
                        "config",
                        getConfig()));

        clusterResourcesRepo.writeFile(
            MONITORING_RBAC_PATH + "/" + currentNamespace + ".yaml", rbacYaml);
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to generate namespace isolation RBAC for " + currentNamespace, e);
      }
    }
  }

  private void generateNetpols(GitRepo clusterResourcesRepo) {
    for (String currentNamespace :
        getConfig().getApplication().getNamespaces().getActiveNamespaces()) {
      try {
        String netpolsYaml =
            new TemplatingEngine()
                .template(
                    new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE),
                    Map.of(
                        NAMESPACE_KEY,
                        currentNamespace,
                        "namePrefix",
                        getConfig().getApplication().getNamePrefix()));

        clusterResourcesRepo.writeFile(
            MONITORING_NETPOLS_PATH + "/" + currentNamespace + ".yaml", netpolsYaml);
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to generate netpols allow template for " + currentNamespace, e);
      }
    }
  }

  private Map<String, String> scmConfigurationMetrics() {
    URI uri = this.gitHandler.getResourcesScm().prometheusMetricsEndpoint();
    return uriComponents(uri);
  }

  private static Map<String, String> uriComponents(URI uri) {
    return Map.of(
        "protocol", (uri != null && uri.getScheme() != null) ? uri.getScheme() : "",
        "host", (uri != null && uri.getAuthority() != null) ? uri.getAuthority() : "",
        "path", (uri != null && uri.getPath() != null) ? uri.getPath() : "");
  }

  protected void createMonitoringCrd() {
    if (!getConfig().getApplication().getSkipCrds()) {
      String serviceMonitorCrdYaml;
      if (context.isAirgapped()) {
        serviceMonitorCrdYaml =
            Path.of(
                    getConfig().getApplication().getLocalHelmChartFolder()
                        + "/"
                        + getConfig().getFeatures().getMonitoring().getHelm().getChart(),
                    "charts/crds/crds/crd-servicemonitors.yaml")
                .toString();
      } else {
        serviceMonitorCrdYaml =
            "https://raw.githubusercontent.com/prometheus-community/helm-charts/"
                + "kube-prometheus-stack-"
                + getConfig().getFeatures().getMonitoring().getHelm().getVersion()
                + "/"
                + "charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml";
      }

      log.debug(
          "Applying ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.\n"
              + "Applying from path {}",
          serviceMonitorCrdYaml);
      k8sClient.applyYaml(serviceMonitorCrdYaml);
    }
  }

  private Map<String, String> jenkinsConfigurationMetrics() {
    URI uri = baseUriJenkins(getConfig()).resolve("prometheus");
    Map<String, String> components = new HashMap<>(uriComponents(uri));
    components.put(
        "metricsUsername",
        (getConfig().getJenkins().getMetricsUsername() != null)
            ? getConfig().getJenkins().getMetricsUsername()
            : "");
    return components;
  }

  private static URI baseUriJenkins(Config config) {
    try {
      if (config.getJenkins().getInternal()) {
        return new URI(
            "http://jenkins."
                + config.getApplication().getNamePrefix()
                + config.getJenkins().getNamespace()
                + ".svc.cluster.local/");
      }
      String urlString =
          config.getJenkins().getUrl() != null ? config.getJenkins().getUrl().trim() : "";
      if (urlString.isEmpty()) {
        throw new IllegalArgumentException(
            "config.jenkins.url must be set when config.jenkins.internal = false");
      }
      URI url = URI.create(urlString);
      return url.toString().endsWith("/") ? url : URI.create(url.toString() + "/");
    } catch (Exception e) {
      throw new RuntimeException("Failed to construct base Jenkins URI", e);
    }
  }

  private String findValidOpenShiftUid() {
    String uidRange =
        k8sClient.getAnnotation(NAMESPACE_KEY, namespace, "openshift.io/sa.scc.uid-range");

    if (uidRange != null && !uidRange.isEmpty()) {
      log.debug("found UID={}", uidRange);
      return uidRange.split("/")[0];
    } else {
      throw new IllegalStateException("Could not find a valid UID! Really running on OpenShift?");
    }
  }

  protected void cleanupUnusedDashboards(GitRepo clusterResourcesRepo) {
    String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir();
    String dashboardRoot = repoRoot + "/" + MONITORING_DASHBOARD_PATH;

    if (!getConfig().getFeatures().getIngress().getActive()) {
      FileSystemUtils.deleteFile(dashboardRoot + "/traefik-dashboard.yaml");
      FileSystemUtils.deleteFile(dashboardRoot + "/traefik-dashboard-requests-handling.yaml");
    }

    if (!getConfig().getJenkins().getActive()) {
      FileSystemUtils.deleteFile(dashboardRoot + "/jenkins-dashboard.yaml");
    }

    if (!hasScmManagerMetricsEndpoint()) {
      FileSystemUtils.deleteFile(dashboardRoot + "/scmm-dashboard.yaml");
    }
  }

  private boolean hasScmManagerMetricsEndpoint() {
    URI uri = this.gitHandler.getResourcesScm().prometheusMetricsEndpoint();

    if (uri == null) {
      return false;
    }

    return hasText(uri.getScheme()) || hasText(uri.getAuthority()) || hasText(uri.getPath());
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  @Override
  public String getActiveNamespaceFromFeature(DeploymentContext context) {
    return isEnabled(context) ? activeNamespace(context) : null;
  }
}
