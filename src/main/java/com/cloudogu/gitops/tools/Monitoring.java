package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import com.cloudogu.gitops.utils.Tuple;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Singleton
@Order(300)
@Slf4j
public class Monitoring extends AbstractMappedTool<MonitoringToolConfig> {

	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml";
	public static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = "argocd/cluster-resources/apps/monitoring/templates/rbac/namespace-isolation-rbac.ftl.yaml";
	public static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE = "argocd/cluster-resources/apps/monitoring/templates/netpols/prometheus-allow-scraping.ftl.yaml";

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

	@Getter
	@Setter
	private String namespace;

	public Monitoring(
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator,
		MonitoringToolConfigMapper configMapper) {
		super(configMapper);
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.k8sClient = k8sClient;
		this.airGappedUtils = airGappedUtils;
		this.gitHandler = gitHandler;
		this.imagePullSecretCreator = imagePullSecretCreator;
	}

	@Override
	protected boolean isEnabled(MonitoringToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		this.namespace = activeNamespace(toolConfig());

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
		addHelmValuesData("config", toolConfig().templateConfig());
		deployHelmChart(TOOL_NAME, RELEASE_NAME, namespace, toolConfig().helm(), HELM_VALUES_PATH, context);
	}

	@Override
	protected void publishChanges() {
		// We always assume internal monitoring for deploying artifacts
		publishClusterResourcesChanges(TOOL_NAME);
	}

	@Override
	protected String activeNamespace(MonitoringToolConfig config) {
		return config.namespace();
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(toolConfig().imagePullSecret(), namespace);
	}

	private void prepareMonitoringHelmValues() {
		String uid = "";
		if (toolConfig().openshift()) {
			uid = findValidOpenShiftUid();
		}

		String grafanaUrl = toolConfig().grafanaUrl();
		String host = "";
		try {
			if (grafanaUrl != null && !grafanaUrl.isEmpty()) {
				host = URI.create(grafanaUrl).toURL().getHost();
			}
		} catch (IllegalArgumentException | MalformedURLException e) {
			throw new IllegalArgumentException("Failed to parse Grafana URL: " + grafanaUrl, e);
		}

		addHelmValuesData(TOOL_NAME, Map.of("grafana", Map.of("host", host)));
		addHelmValuesData(
			"namespaces", toolConfig().activeNamespaces()
		);
		addHelmValuesData("scm", scmConfigurationMetrics());
		addHelmValuesData("jenkins", jenkinsConfigurationMetrics());
		addHelmValuesData("uid", uid);
	}

	private void prepareMonitoringApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing Monitoring repository content in {}", clusterResourcesRepo.getRepoTarget());

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, MONITORING_APP_PATH)
		);
	}

	private void replaceMonitoringTemplates(GitRepo clusterResourcesRepo) {
		clusterResourcesRepo.replaceTemplates(Map.of("config", toolConfig().templateConfig()));
	}

	private void writeMonitoringGitOpsArtifacts(GitRepo clusterResourcesRepo) {
		if (toolConfig().namespaceIsolation()) {
			generateNamespaceIsolationRBAC(clusterResourcesRepo);
		}

		if (toolConfig().netpols()) {
			generateNetpols(clusterResourcesRepo);
		}

		// Remove dashboards for features that are not enabled
		cleanupUnusedDashboards(clusterResourcesRepo);
	}

	private void setupMonitoringSecrets() {
		k8sClient.createSecret(
			GENERIC_SECRET_TYPE, "prometheus-metrics-creds-scmm", namespace, new Tuple<>(
				PASSWORD_KEY, toolConfig().applicationPassword()
			)
		);

		k8sClient.createSecret(
			GENERIC_SECRET_TYPE, "prometheus-metrics-creds-jenkins", namespace, new Tuple<>(
				PASSWORD_KEY, toolConfig().jenkinsMetricsPassword()
			)
		);

		if (hasText(toolConfig().smtpUser()) || hasText(toolConfig().smtpPassword())) {
			k8sClient.createSecret(
				GENERIC_SECRET_TYPE, "grafana-email-secret", namespace, new Tuple<>(
					"user", toolConfig().smtpUser()
				), new Tuple<>(
					PASSWORD_KEY, toolConfig().smtpPassword()
				)
			);
		}
	}

	private void generateNamespaceIsolationRBAC(GitRepo clusterResourcesRepo) {
		for (String currentNamespace : toolConfig().activeNamespaces()) {
			try {
				String rbacYaml = new TemplatingEngine().template(
					new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE), Map.of(
						NAMESPACE_KEY,
						currentNamespace,
						"namePrefix",
						toolConfig().namePrefix(),
						"config",
						toolConfig().templateConfig()
					)
				);

				clusterResourcesRepo.writeFile(MONITORING_RBAC_PATH + "/" + currentNamespace + ".yaml", rbacYaml);
			} catch (Exception e) {
				throw new RuntimeException("Failed to generate namespace isolation RBAC for " + currentNamespace, e);
			}
		}
	}

	private void generateNetpols(GitRepo clusterResourcesRepo) {
		for (String currentNamespace : toolConfig().activeNamespaces()) {
			try {
				String netpolsYaml = new TemplatingEngine().template(
					new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE), Map.of(
						NAMESPACE_KEY, currentNamespace, "namePrefix", toolConfig().namePrefix()
					)
				);

				clusterResourcesRepo.writeFile(MONITORING_NETPOLS_PATH + "/" + currentNamespace + ".yaml", netpolsYaml);
			} catch (Exception e) {
				throw new RuntimeException("Failed to generate netpols allow template for " + currentNamespace, e);
			}
		}
	}

	private Map<String, String> scmConfigurationMetrics() {
		URI uri = this.gitHandler.getResourcesScm().prometheusMetricsEndpoint();
		return uriComponents(uri);
	}

	private static Map<String, String> uriComponents(URI uri) {
		if (uri == null) {
			return Map.of("protocol", "", "host", "", "path", "");
		}
		return Map.of(
			"protocol",
			Objects.requireNonNullElse(uri.getScheme(), ""),
			"host",
			Objects.requireNonNullElse(uri.getAuthority(), ""),
			"path",
			Objects.requireNonNullElse(uri.getPath(), "")
		);
	}

	protected void createMonitoringCrd() {
		if (!toolConfig().skipCrds()) {
			String serviceMonitorCrdYaml;
			if (toolConfig().airgapped()) {
				serviceMonitorCrdYaml = Path.of(
												toolConfig().helm().localHelmChartFolder() + "/" + toolConfig().helm().chart(),
												"charts/crds/crds/crd-servicemonitors.yaml"
											)
											.toString();
			} else {
				serviceMonitorCrdYaml = "https://raw.githubusercontent.com/prometheus-community/helm-charts/" + "kube-prometheus-stack-" + toolConfig().helm().version() + "/" + "charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml";
			}

			log.debug(
				"Applying ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.\n" + "Applying from path {}",
				serviceMonitorCrdYaml
			);
			k8sClient.applyYaml(serviceMonitorCrdYaml);
		}
	}

	private Map<String, String> jenkinsConfigurationMetrics() {
		URI uri = baseUriJenkins(toolConfig()).resolve("prometheus");
		Map<String, String> components = new HashMap<>(uriComponents(uri));
		components.put(
			"metricsUsername", toolConfig().jenkinsMetricsUsername() != null
				? toolConfig().jenkinsMetricsUsername()
				: ""
		);
		return components;
	}

	private static URI baseUriJenkins(MonitoringToolConfig config) {
		try {
			if (config.jenkinsInternal()) {
				return new URI("http://jenkins." + config.namePrefix() + config.jenkinsNamespace() + ".svc.cluster.local/");
			}
			String urlString = config.jenkinsUrl() != null ? config.jenkinsUrl().trim() : "";
			if (urlString.isEmpty()) {
				throw new IllegalArgumentException("config.jenkins.url must be set when config.jenkins.internal = false");
			}
			URI url = URI.create(urlString);
			return url.toString().endsWith("/") ? url : URI.create(url.toString() + "/");
		} catch (Exception e) {
			throw new RuntimeException("Failed to construct base Jenkins URI", e);
		}
	}

	private String findValidOpenShiftUid() {
		String uidRange = k8sClient.getAnnotation(NAMESPACE_KEY, namespace, "openshift.io/sa.scc.uid-range");

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

		if (!toolConfig().ingressActive()) {
			FileSystemUtils.deleteFile(dashboardRoot + "/traefik-dashboard.yaml");
			FileSystemUtils.deleteFile(dashboardRoot + "/traefik-dashboard-requests-handling.yaml");
		}

		if (!toolConfig().jenkinsActive()) {
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

}
