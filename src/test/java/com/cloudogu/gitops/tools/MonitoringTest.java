package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.Tuple;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
@SuppressWarnings("unchecked")
class MonitoringTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};

	private final Config config = Config.fromMap(Map.of(
		"registry", Map.of(
			"internal", true,
			"createImagePullSecrets", false
		),
		"scm", Map.of(
			"scmManager", Map.of("internal", true)
		),
		"jenkins", Map.of(
			"internal", true,
			"active", true,
			"metricsUsername", "metrics",
			"metricsPassword", "metrics"
		),
		"application", Map.ofEntries(
			Map.entry("username", "abc"),
			Map.entry("password", "123"),
			Map.entry("openshift", false),
			Map.entry("namePrefix", "foo-"),
			Map.entry("mirrorRepos", false),
			Map.entry("podResources", false),
			Map.entry("skipCrds", false),
			Map.entry("namespaceIsolation", false),
			Map.entry("gitName", "Cloudogu"),
			Map.entry("gitEmail", "hello@cloudogu.com"),
			Map.entry("netpols", false),
			Map.entry(
				"namespaces", Map.of(
					"dedicatedNamespaces", new LinkedHashSet<>(List.of(
						"test1-default",
						"test1-argocd",
						"test1-monitoring",
						"test1-secrets"
					)),
					"tenantNamespaces", new LinkedHashSet<>(List.of(
						"test1-example-apps-staging",
						"test1-example-apps-production"
					))
				)
			)
		),
		"features", Map.of(
			"argocd", Map.of("active", true),
			"monitoring", Map.of(
				"active", true,
				"grafanaUrl", "",
				"grafanaEmailFrom", "grafana@example.org",
				"grafanaEmailTo", "infra@example.org",
				"helm", Map.of(
					"chart", "kube-prometheus-stack",
					"repoURL", "https://prom",
					"version", "19.2.2"
				)
			),
			"secrets", Map.of("active", true),
			"ingress", Map.of("active", true)
		)
	));

	private K8sClient k8sClient;
	private final Deployer deployer = mock(Deployer.class);
	private final AirGappedUtils airGappedUtils = mock(AirGappedUtils.class);
	private Path temporaryYamlFilePrometheus;
	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();
	private File clusterResourcesRepoDir;

	private final GitHandler gitHandler = mock(GitHandler.class);
	private RepositoryWorkspace repositoryWorkspace;
	private DeploymentContext deploymentContext;
	private ScmManagerProviderMock scmManagerMock;
	private final ImagePullSecretCreator imagePullSecretCreator = mock(ImagePullSecretCreator.class);

	KubernetesClient client;
	KubernetesMockServer server;

	@BeforeEach
	void setup() {
		scmManagerMock = new ScmManagerProviderMock();
		k8sClient = mock(K8sClient.class);
		k8sClient.setClient(client);
	}

	@Test
	void isDisabledViaActiveFlag() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(false);
		assertFalse(createStack(scmManagerMock).isEnabled(new ContextBuilder(config).build()));
	}

	@Test
	void whenMailServerDisabledDoesNotIncludeMailConfigurationsIntoClusterResources() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(null);
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		assertThat(grafana.get("notifiers")).isNull();
	}

	@Test
	void whenMailServerEnabledIncludesMailConfigurationsIntoClusterResources() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		assertThat(grafana.get("notifiers")).isNotNull();
	}

	@Test
	void whenEmailAddressesIsSet() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMonitoring().setGrafanaEmailFrom("grafana@example.com");
		config.getFeatures().getMonitoring().setGrafanaEmailTo("infra@example.com");
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> notifiers = (Map<String, Object>) grafana.get("notifiers");
		Map<String, Object> notifiersYaml = (Map<String, Object>) notifiers.get("notifiers.yaml");
		List<Map<String, Object>> notifierList = (List<Map<String, Object>>) notifiersYaml.get("notifiers");
		Map<String, Object> settings = (Map<String, Object>) notifierList.get(0).get("settings");

		assertThat(settings.get("addresses")).isEqualTo("infra@example.com");

		Map<String, Object> env = (Map<String, Object>) grafana.get("env");
		assertThat(env.get("GF_SMTP_FROM_ADDRESS")).isEqualTo("grafana@example.com");
	}

	@Test
	void whenEmailAddressesIsNotSet() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> notifiers = (Map<String, Object>) grafana.get("notifiers");
		Map<String, Object> notifiersYaml = (Map<String, Object>) notifiers.get("notifiers.yaml");
		List<Map<String, Object>> notifierList = (List<Map<String, Object>>) notifiersYaml.get("notifiers");
		Map<String, Object> settings = (Map<String, Object>) notifierList.get(0).get("settings");

		assertThat(settings.get("addresses")).isEqualTo("infra@example.org");

		Map<String, Object> env = (Map<String, Object>) grafana.get("env");
		assertThat(env.get("GF_SMTP_FROM_ADDRESS")).isEqualTo("grafana@example.org");
	}

	@Test
	void whenExternalMailserverIsSet() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpPort(1010110);
		config.getFeatures().getMonitoring().setGrafanaEmailTo("grafana@example.com");

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> alerting = (Map<String, Object>) grafana.get("alerting");

		Map<String, Object> expectedContactPoints = YAML_MAPPER.readValue(
			"""
				apiVersion: 1
				contactPoints:
				- orgId: 1
				  name: email
				  is_default: true
				  receivers:
				  - uid: email1
				    type: email
				    settings:
				      addresses: grafana@example.com
				""", YAML_MAP_TYPE
		);
		assertThat(alerting.get("contactpoints.yaml")).isEqualTo(expectedContactPoints);

		Map<String, Object> expectedNotificationPolicies = YAML_MAPPER.readValue(
			"""
				apiVersion: 1
				policies:
				- orgId: 1
				  is_default: true
				  receiver: email
				  routes:
				  - receiver: email
				  group_by: ["grafana_folder", "alertname"]
				""", YAML_MAP_TYPE
		);
		assertThat(alerting.get("notification-policies.yaml")).isEqualTo(expectedNotificationPolicies);

		Map<String, Object> env = (Map<String, Object>) grafana.get("env");
		assertThat(env.get("GF_SMTP_HOST")).isEqualTo("smtp.example.com:1010110");
	}

	@Test
	void whenExternalMailserverIsSetWithUser() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpUser("mailserver@example.com");

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> smtp = (Map<String, Object>) grafana.get("smtp");
		assertThat(smtp.get("existingSecret")).isEqualTo("grafana-email-secret");
	}

	@Test
	void whenExternalMailserverUserContainsOnlyWhitespaceItIsStillTreatedAsConfigured() throws GitAPIException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpUser("   ");

		install(createStack(scmManagerMock));

		verify(k8sClient).createSecret(
			"generic",
			"grafana-email-secret",
			"foo-monitoring",
			new Tuple<>("user", "   "),
			new Tuple<>("password", "")
		);
	}

	@Test
	void whenExternalMailserverIsSetWithPassword() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpPassword("1101ABCabc&/+*~");

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> smtp = (Map<String, Object>) grafana.get("smtp");
		assertThat(smtp.get("existingSecret")).isEqualTo("grafana-email-secret");
	}

	@Test
	void whenExternalMailserverIsSetWithoutUserAndPassword() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		assertThat(grafana.get("valuesFrom")).isNull();
		assertThat(grafana.get("smtp")).isNull();
	}

	@Test
	void checkIfKubernetesSecretWillBeCreatedWhenExternalEmailserversCredentialIsSet() throws GitAPIException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");
		config.getFeatures().getMail().setSmtpUser("grafana@example.com");
		config.getFeatures().getMail().setSmtpPassword("1101ABCabc&/+*~");

		install(createStack(scmManagerMock));
	}

	@Test
	void whenExternalMailserverIsSetWithoutPort() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.com");

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> env = (Map<String, Object>) grafana.get("env");
		assertThat(env.get("GF_SMTP_HOST")).isEqualTo("smtp.example.com");
	}

	@Test
	void whenExternalMailserverIsNotSet() throws GitAPIException, IOException {
		config.getFeatures().getMail().setActive(null);
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		assertThat(grafana.get("alerting")).isNull();
	}

	@Test
	void configuresAdminUserIfRequested() throws GitAPIException, IOException {
		config.getApplication().setUsername("my-user");
		config.getApplication().setPassword("hunter2");
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		assertThat(grafana.get("adminUser")).isEqualTo("my-user");
		assertThat(grafana.get("adminPassword")).isEqualTo("hunter2");
	}

	@Test
	void configuresGrafanaOidcFromStructuredConfig() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().setGrafanaUrl("http://grafana.localhost");

		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setClientId("grafana");
		oidc.setClientSecret("grafana-secret");
		oidc.setAdminGroupName("gop-admins");
		config.getFeatures().getMonitoring().setOidc(oidc);

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> grafanaIni = (Map<String, Object>) grafana.get("grafana.ini");
		Map<String, Object> oauth = (Map<String, Object>) grafanaIni.get("auth.generic_oauth");

		assertThat(oauth.get("enabled")).isEqualTo(true);
		assertThat(oauth.get("client_id")).isEqualTo("grafana");
		assertThat(oauth.get("auth_url")).isEqualTo("http://keycloak.local.gd/realms/gop/protocol/openid-connect/auth");
		assertThat(oauth.get("role_attribute_path")).isEqualTo("contains(groups[*], 'gop-admins') && 'Admin' || 'None'");
		assertThat(oauth.get("role_attribute_strict")).isEqualTo(true);
	}

	@Test
	void doesNotConfigureGrafanaOidcWhenOidcConfigIsNull() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().setOidc(null);

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> grafanaIni = (Map<String, Object>) grafana.get("grafana.ini");
		assertThat(grafanaIni).doesNotContainKey("auth.generic_oauth");
	}

	@Test
	void usesDefaultGrafanaOidcScopesWhenScopesAreNull() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().setGrafanaUrl("http://grafana.localhost");

		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setClientId("grafana");
		oidc.setClientSecret("grafana-secret");
		oidc.setScopes(null);
		config.getFeatures().getMonitoring().setOidc(oidc);

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> grafanaIni = (Map<String, Object>) grafana.get("grafana.ini");
		Map<String, Object> oauth = (Map<String, Object>) grafanaIni.get("auth.generic_oauth");
		assertThat(oauth.get("scopes")).isEqualTo("openid profile email");
	}

	@Test
	void usesIngressIfEnabled() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().setGrafanaUrl("http://grafana.local");

		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> serviceYaml = (Map<String, Object>) grafana.get("ingress");
		assertThat(serviceYaml.get("enabled")).isEqualTo(true);
		assertThat(((List<Object>) serviceYaml.get("hosts")).get(0)).isEqualTo("grafana.local");
	}

	@Test
	void doesNotUseIngressByDefault() throws GitAPIException, IOException {
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		assertThat(grafana).doesNotContainKey("ingress");
	}

	@Test
	void preparesMonitoringAppContentInClusterResourcesWorkspaceWithoutCopyingTemplates() throws GitAPIException {
		install(createStack(scmManagerMock));

		assertThat(new File(clusterResourcesRepoDir, "apps/monitoring")).exists();
		assertThat(new File(clusterResourcesRepoDir, "apps/monitoring/templates")).doesNotExist();
		assertThat(new File(clusterResourcesRepoDir, "apps/monitoring/misc/dashboard")).exists();
	}

	@Test
	void cleanupUnusedDashboardsRemovesAllDashboardsForDisabledFeatures() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getIngress().setActive(false);
		config.getJenkins().setActive(false);
		scmManagerMock.setPrometheus(null);

		install(createStack(scmManagerMock));

		File dashboardDir = new File(clusterResourcesRepoDir, "apps/monitoring/misc/dashboard");

		assertThat(new File(dashboardDir, "traefik-dashboard.yaml")).doesNotExist();
		assertThat(new File(dashboardDir, "traefik-dashboard-requests-handling.yaml")).doesNotExist();
		assertThat(new File(dashboardDir, "jenkins-dashboard.yaml")).doesNotExist();
		assertThat(new File(dashboardDir, "scmm-dashboard.yaml")).doesNotExist();
	}

	@Test
	void cleanupUnusedDashboardsKeepsScmmDashboardWhenInternalScmMetricsEndpointExists() throws GitAPIException, URISyntaxException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getIngress().setActive(false);
		config.getJenkins().setActive(false);
		config.getScm().getScmManager().setUrl(null);
		scmManagerMock.setPrometheus(new URI("http://localhost:8080/scm/api/v2/metrics/prometheus"));

		install(createStack(scmManagerMock));

		File dashboardDir = new File(clusterResourcesRepoDir, "apps/monitoring/misc/dashboard");

		assertThat(new File(dashboardDir, "traefik-dashboard.yaml")).doesNotExist();
		assertThat(new File(dashboardDir, "traefik-dashboard-requests-handling.yaml")).doesNotExist();
		assertThat(new File(dashboardDir, "jenkins-dashboard.yaml")).doesNotExist();
		assertThat(new File(dashboardDir, "scmm-dashboard.yaml")).exists();
	}

	@Test
	void appliesPrometheusServiceMonitorCrdFromFileBeforeInstallingAirGappedMode() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getApplication().setMirrorRepos(true);
		config.getApplication().setSkipCrds(false);

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path crdFile = rootChartsFolder.resolve(
			config.getFeatures().getMonitoring().getHelm().getChart() + "/charts/crds/crds/crd-servicemonitors.yaml"
		);
		Files.createDirectories(crdFile.getParent());
		Files.writeString(crdFile, "dummy");

		Path chartYaml = rootChartsFolder.resolve(config.getFeatures().getMonitoring().getHelm().getChart() + "/Chart.yaml");
		Files.createDirectories(chartYaml.getParent());
		Files.writeString(chartYaml, "apiVersion: v2\nname: kube-prometheus-stack\nversion: 42.0.3\n");

		install(createStack(scmManagerMock));
	}

	@Test
	void appliesPrometheusServiceMonitorCrdFromGithubBeforeInstalling() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getApplication().setMirrorRepos(false);
		config.getApplication().setSkipCrds(false);

		install(createStack(scmManagerMock));
	}

	@Test
	void doesNotApplyServiceMonitorCrdWhenMonitoringIsDisabled() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(false);
		config.getApplication().setSkipCrds(false);
		config.getApplication().setMirrorRepos(false);

		install(createStack(scmManagerMock));
	}

	@Test
	void usesRemoteScmmUrlIfRequested() throws GitAPIException, IOException {
		install(createStack(scmManagerMock));

		Map<String, Object> prometheus = (Map<String, Object>) parseActualYaml().get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");
		List<Map<String, Object>> additionalScrapeConfigs =
			(List<Map<String, Object>>) prometheusSpec.get("additionalScrapeConfigs");

		List<Map<String, Object>> staticConfigs0 = (List<Map<String, Object>>) additionalScrapeConfigs.get(0).get(
			"static_configs");
		List<Object> targets0 = (List<Object>) staticConfigs0.get(0).get("targets");
		assertThat(targets0.get(0)).isEqualTo("localhost:8080");
		assertThat(additionalScrapeConfigs.get(0).get("metrics_path")).isEqualTo("/scm/api/v2/metrics/prometheus");
		assertThat(additionalScrapeConfigs.get(0).get("scheme")).isEqualTo("http");

		List<Map<String, Object>> staticConfigs1 = (List<Map<String, Object>>) additionalScrapeConfigs.get(1).get(
			"static_configs");
		List<Object> targets1 = (List<Object>) staticConfigs1.get(0).get("targets");
		assertThat(targets1.get(0)).isEqualTo("jenkins.foo-jenkins.svc.cluster.local");
		assertThat(additionalScrapeConfigs.get(1).get("scheme")).isEqualTo("http");
		assertThat(additionalScrapeConfigs.get(1).get("metrics_path")).isEqualTo("/prometheus");
	}

	@Test
	void usesRemoteJenkinsUrlIfRequested() throws GitAPIException, IOException {
		config.getJenkins().setInternal(false);
		config.getJenkins().setUrl("https://localhost:9090/jenkins");
		install(createStack(scmManagerMock));

		Map<String, Object> prometheus = (Map<String, Object>) parseActualYaml().get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");
		List<Map<String, Object>> additionalScrapeConfigs =
			(List<Map<String, Object>>) prometheusSpec.get("additionalScrapeConfigs");

		List<Map<String, Object>> staticConfigs0 = (List<Map<String, Object>>) additionalScrapeConfigs.get(0).get(
			"static_configs");
		List<Object> targets0 = (List<Object>) staticConfigs0.get(0).get("targets");
		assertThat(targets0.get(0)).isEqualTo("localhost:8080");
		assertThat(additionalScrapeConfigs.get(0).get("scheme")).isEqualTo("http");
		assertThat(additionalScrapeConfigs.get(0).get("metrics_path")).isEqualTo("/scm/api/v2/metrics/prometheus");

		List<Map<String, Object>> staticConfigs1 = (List<Map<String, Object>>) additionalScrapeConfigs.get(1).get(
			"static_configs");
		List<Object> targets1 = (List<Object>) staticConfigs1.get(0).get("targets");
		assertThat(targets1.get(0)).isEqualTo("localhost:9090");
		assertThat(additionalScrapeConfigs.get(1).get("metrics_path")).isEqualTo("/jenkins/prometheus");
		assertThat(additionalScrapeConfigs.get(1).get("scheme")).isEqualTo("https");
	}

	@Test
	void configuresCustomMetricsUserForJenkins() throws GitAPIException, IOException {
		config.getJenkins().setMetricsUsername("external-metrics-username");
		config.getJenkins().setMetricsPassword("hunter2");
		install(createStack(scmManagerMock));

		Map<String, Object> prometheus = (Map<String, Object>) parseActualYaml().get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");
		List<Map<String, Object>> additionalScrapeConfigs =
			(List<Map<String, Object>>) prometheusSpec.get("additionalScrapeConfigs");
		Map<String, Object> basicAuth = (Map<String, Object>) additionalScrapeConfigs.get(1).get("basic_auth");
		assertThat(basicAuth.get("username")).isEqualTo("external-metrics-username");
	}

	@Test
	void configuresCustomImageForGrafana() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().getHelm().setGrafanaImage("localhost:5000/grafana/grafana:the-tag");
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> image = (Map<String, Object>) grafana.get("image");
		assertThat(image.get("registry")).isEqualTo("localhost:5000");
		assertThat(image.get("repository")).isEqualTo("grafana/grafana");
		assertThat(image.get("tag")).isEqualTo("the-tag");
	}

	@Test
	void configuresCustomImageForGrafanaSidecar() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().getHelm().setGrafanaSidecarImage("localhost:5000/grafana/sidecar:the-tag");
		install(createStack(scmManagerMock));

		Map<String, Object> grafana = (Map<String, Object>) parseActualYaml().get("grafana");
		Map<String, Object> sidecar = (Map<String, Object>) grafana.get("sidecar");
		Map<String, Object> image = (Map<String, Object>) sidecar.get("image");
		assertThat(image.get("registry")).isEqualTo("localhost:5000");
		assertThat(image.get("repository")).isEqualTo("grafana/sidecar");
		assertThat(image.get("tag")).isEqualTo("the-tag");
	}

	@Test
	void configuresCustomImageForPrometheusAndOperator() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().getHelm().setPrometheusImage("localhost:5000/prometheus/prometheus:v1");
		config.getFeatures().getMonitoring().getHelm().setPrometheusOperatorImage(
			"localhost:5000/prometheus-operator/prometheus-operator:v2"
		);
		config.getFeatures().getMonitoring().getHelm().setPrometheusConfigReloaderImage(
			"localhost:5000/prometheus-operator/prometheus-config-reloader:v3"
		);

		install(createStack(scmManagerMock));

		Map<String, Object> actualYaml = parseActualYaml();
		Map<String, Object> prometheus = (Map<String, Object>) actualYaml.get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");
		Map<String, Object> prometheusImage = (Map<String, Object>) prometheusSpec.get("image");
		assertThat(prometheusImage.get("registry")).isEqualTo("localhost:5000");
		assertThat(prometheusImage.get("repository")).isEqualTo("prometheus/prometheus");
		assertThat(prometheusImage.get("tag")).isEqualTo("v1");

		Map<String, Object> prometheusOperator = (Map<String, Object>) actualYaml.get("prometheusOperator");
		Map<String, Object> operatorImage = (Map<String, Object>) prometheusOperator.get("image");
		assertThat(operatorImage.get("registry")).isEqualTo("localhost:5000");
		assertThat(operatorImage.get("repository")).isEqualTo("prometheus-operator/prometheus-operator");
		assertThat(operatorImage.get("tag")).isEqualTo("v2");

		Map<String, Object> configReloader = (Map<String, Object>) prometheusOperator.get("prometheusConfigReloader");
		Map<String, Object> reloaderImage = (Map<String, Object>) configReloader.get("image");
		assertThat(reloaderImage.get("registry")).isEqualTo("localhost:5000");
		assertThat(reloaderImage.get("repository")).isEqualTo("prometheus-operator/prometheus-config-reloader");
		assertThat(reloaderImage.get("tag")).isEqualTo("v3");
	}

	@Test
	void deploysImagePullSecretsForProxyRegistry() throws GitAPIException, IOException {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-pw");

		install(createStack(scmManagerMock));

		Map<String, Object> global = (Map<String, Object>) parseActualYaml().get("global");
		assertThat(global.get("imagePullSecrets")).isEqualTo(List.of(Map.of("name", "proxy-registry")));
	}

	@Test
	void helmReleaseIsInstalled() throws GitAPIException, IOException {
		install(createStack(scmManagerMock));

		verify(deployer).deployFeature(
			"https://prom",
			"monitoring",
			"kube-prometheus-stack",
			"19.2.2",
			"foo-monitoring",
			"kube-prometheus-stack",
			temporaryYamlFilePrometheus,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace
		);

		Map<String, Object> yaml = parseActualYaml();
		Map<String, Object> grafana = (Map<String, Object>) yaml.get("grafana");
		assertThat(grafana.get("adminUser")).isEqualTo("abc");
		assertThat(grafana.get("adminPassword")).isEqualTo(123);

		Map<String, Object> prometheusOperator = (Map<String, Object>) yaml.get("prometheusOperator");
		Map<String, Object> sidecar = (Map<String, Object>) grafana.get("sidecar");
		Map<String, Object> prometheus = (Map<String, Object>) yaml.get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");

		assertThat(prometheusOperator).doesNotContainKey("resources");
		assertThat(grafana).doesNotContainKey("resources");
		assertThat(sidecar).doesNotContainKey("resources");
		assertThat(prometheusSpec).doesNotContainKey("resources");

		assertThat(prometheusOperator.get("securityContext")).isNull();
		assertThat(grafana.get("securityContext")).isNull();
		assertThat(prometheusSpec.get("securityContext")).isNull();

		assertThat(yaml.get("kubeApiServer")).isNull();

		Map<String, Object> admissionWebhooks = (Map<String, Object>) prometheusOperator.get("admissionWebhooks");
		assertThat(admissionWebhooks.get("enabled")).isEqualTo(false);
		Map<String, Object> tls = (Map<String, Object>) prometheusOperator.get("tls");
		assertThat(tls.get("enabled")).isEqualTo(false);
		assertThat(prometheusOperator.get("kubeletService")).isNull();
		assertThat(prometheusOperator.get("namespaces")).isNull();
		assertThat(yaml).doesNotContainKey("global");

		assertThat(grafana.get("rbac")).isNull();
		Map<String, Object> dashboards = (Map<String, Object>) sidecar.get("dashboards");
		assertThat(dashboards.get("searchNamespace")).isEqualTo("ALL");

		assertThat(yaml.get("crds")).isNull();
		assertThat(new File(clusterResourcesRepoDir, "apps/monitoring/misc/rbac")).doesNotExist();
	}

	@Test
	void publishesMonitoringResourcesThroughRepositoryWorkspace() throws GitAPIException {
		install(createStack(scmManagerMock));

		verify(repositoryWorkspace).commitAndPushClusterResourcesChanges("Update monitoring GitOps resources");
	}

	@Test
	void skipsCrds() throws GitAPIException, IOException {
		config.getApplication().setSkipCrds(true);

		install(createStack(scmManagerMock));

		Map<String, Object> crds = (Map<String, Object>) parseActualYaml().get("crds");
		assertThat(crds.get("enabled")).isEqualTo(false);
	}

	@Test
	void setsPodResourceLimitsAndRequests() throws GitAPIException, IOException {
		config.getApplication().setPodResources(true);

		install(createStack(scmManagerMock));

		Map<String, Object> yaml = parseActualYaml();
		Map<String, Object> prometheusOperator = (Map<String, Object>) yaml.get("prometheusOperator");
		Map<String, Object> configReloader = (Map<String, Object>) prometheusOperator.get("prometheusConfigReloader");
		Map<String, Object> grafana = (Map<String, Object>) yaml.get("grafana");
		Map<String, Object> sidecar = (Map<String, Object>) grafana.get("sidecar");
		Map<String, Object> prometheus = (Map<String, Object>) yaml.get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");

		assertThat((Map<String, Object>) prometheusOperator.get("resources")).containsKeys("limits", "requests");
		assertThat((Map<String, Object>) configReloader.get("resources")).containsKeys("limits", "requests");
		assertThat((Map<String, Object>) grafana.get("resources")).containsKeys("limits", "requests");
		assertThat((Map<String, Object>) sidecar.get("resources")).containsKeys("limits", "requests");
		assertThat((Map<String, Object>) prometheusSpec.get("resources")).containsKeys("limits", "requests");
	}

	@Test
	void worksWithOpenshift() throws GitAPIException, IOException {
		config.getApplication().setOpenshift(true);
		when(k8sClient.getAnnotation("namespace", "foo-monitoring", "openshift.io/sa.scc.uid-range"))
			.thenReturn("1000920000/10000");
		install(createStack(scmManagerMock));

		Map<String, Object> yaml = parseActualYaml();
		Map<String, Object> prometheusOperator = (Map<String, Object>) yaml.get("prometheusOperator");
		Map<String, Object> operatorSecurityContext = (Map<String, Object>) prometheusOperator.get("securityContext");
		assertThat(operatorSecurityContext).isNotNull();
		assertThat(operatorSecurityContext.get("fsGroup")).isNull();
		assertThat(operatorSecurityContext.get("runAsGroup")).isNull();
		assertThat(operatorSecurityContext.get("runAsUser")).isNull();

		Map<String, Object> grafana = (Map<String, Object>) yaml.get("grafana");
		Map<String, Object> grafanaSecurityContext = (Map<String, Object>) grafana.get("securityContext");
		assertThat(grafanaSecurityContext).isNotNull();
		assertThat(grafanaSecurityContext.get("fsGroup")).isEqualTo(1000920000);
		assertThat(grafanaSecurityContext.get("runAsGroup")).isEqualTo(1000920000);
		assertThat(grafanaSecurityContext.get("runAsUser")).isEqualTo(1000920000);

		Map<String, Object> prometheus = (Map<String, Object>) yaml.get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");
		Map<String, Object> prometheusSecurityContext = (Map<String, Object>) prometheusSpec.get("securityContext");
		assertThat(prometheusSecurityContext).isNotNull();
		assertThat(prometheusSecurityContext.get("fsGroup")).isNull();
		assertThat(prometheusSpec.get("runAsGroup")).isNull();
		assertThat(prometheusSpec.get("runAsUser")).isNull();
	}

	@Test
	void worksWithNamespaceIsolation() throws GitAPIException, IOException {
		config.getApplication().setNamespaceIsolation(true);

		Monitoring prometheusStack = createStack(scmManagerMock);
		install(prometheusStack);

		Map<String, Object> yaml = parseActualYaml();
		Map<String, Object> global = (Map<String, Object>) yaml.get("global");
		Map<String, Object> globalRbac = (Map<String, Object>) global.get("rbac");
		assertThat(globalRbac.get("create")).isEqualTo(false);

		for (String namespace : config.getApplication().getNamespaces().getActiveNamespaces()) {
			File rbacYaml = new File(
				clusterResourcesRepoDir,
				"apps/monitoring/misc/rbac/" + namespace + ".yaml"
			);
			String rbacText = Files.readString(rbacYaml.toPath());
			assertThat(rbacText).contains("namespace: " + namespace);
			assertThat(rbacText).contains("    namespace: foo-monitoring");
		}

		Map<String, Object> kubeApiServer = (Map<String, Object>) yaml.get("kubeApiServer");
		assertThat(kubeApiServer.get("enabled")).isEqualTo(false);

		Map<String, Object> prometheusOperator = (Map<String, Object>) yaml.get("prometheusOperator");
		Map<String, Object> kubeletService = (Map<String, Object>) prometheusOperator.get("kubeletService");
		assertThat(kubeletService.get("enabled")).isEqualTo(false);

		Map<String, Object> namespaces = (Map<String, Object>) prometheusOperator.get("namespaces");
		assertThat(namespaces.get("releaseNamespace")).isEqualTo(false);
		assertThat((List<String>) namespaces.get("additional"))
			.hasSameElementsAs(config.getApplication().getNamespaces().getActiveNamespaces());

		Map<String, Object> grafana = (Map<String, Object>) yaml.get("grafana");
		Map<String, Object> rbac = (Map<String, Object>) grafana.get("rbac");
		assertThat(rbac.get("create")).isEqualTo(false);
		Map<String, Object> sidecar = (Map<String, Object>) grafana.get("sidecar");
		Map<String, Object> dashboards = (Map<String, Object>) sidecar.get("dashboards");
		assertThat(dashboards.get("searchNamespace"))
			.isEqualTo(String.join(",", config.getApplication().getNamespaces().getActiveNamespaces()));
	}

	@Test
	void networkPoliciesAreCreatedForPrometheus() throws GitAPIException, IOException {
		config.getApplication().setNetpols(true);
		Monitoring prometheusStack = createStack(scmManagerMock);
		install(prometheusStack);

		for (String namespace : config.getApplication().getNamespaces().getActiveNamespaces()) {
			File netPolsYaml = new File(
				clusterResourcesRepoDir,
				"apps/monitoring/misc/netpols/" + namespace + ".yaml"
			);
			assertThat(Files.readString(netPolsYaml.toPath())).contains("namespace: " + namespace);
		}
	}

	@Test
	void helmReleasesAreInstalledInAirGappedMode() throws GitAPIException, IOException, URISyntaxException {
		config.getApplication().setMirrorRepos(true);
		when(airGappedUtils.mirrorHelmRepoToGit(any(HelmChartConfig.class))).thenReturn("a/b");

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path prometheusSourceChart = rootChartsFolder.resolve("kube-prometheus-stack");
		Files.createDirectories(prometheusSourceChart);

		Map<String, Object> prometheusChartYaml = Map.of("version", "1.2.3");
		fileSystemUtils.writeYaml(prometheusChartYaml, prometheusSourceChart.resolve("Chart.yaml").toFile());

		scmManagerMock.setInClusterBase(new URI("http://scmm.foo-scm-manager.svc.cluster.local/scm"));
		install(createStack(scmManagerMock));

		ArgumentCaptor<HelmChartConfig> helmConfig = ArgumentCaptor.forClass(HelmChartConfig.class);
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture());
		assertThat(helmConfig.getValue().chart()).isEqualTo("kube-prometheus-stack");
		assertThat(helmConfig.getValue().repoURL()).isEqualTo("https://prom");
		assertThat(helmConfig.getValue().version()).isEqualTo("19.2.2");

		verify(deployer).deployFeature(
			"http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b",
			"monitoring",
			".",
			"1.2.3",
			"foo-monitoring",
			"kube-prometheus-stack",
			temporaryYamlFilePrometheus,
			RepoType.GIT,
			false,
			deploymentContext,
			repositoryWorkspace
		);
	}

	@Test
	void mergesAdditionalHelmValuesMergedWithDefaultValues() throws GitAPIException, IOException {
		Map<String, Object> prometheusSpec = new HashMap<>();
		prometheusSpec.put("scrapeConfigSelectorNilUsesHelmValues", null);

		Map<String, Object> prometheus = new HashMap<>();
		prometheus.put("prometheusSpec", prometheusSpec);

		Map<String, Object> values = new HashMap<>();
		values.put("key", Map.of("some", "thing", "one", 1));
		values.put("prometheus", prometheus);
		config.getFeatures().getMonitoring().getHelm().setValues(values);

		install(createStack(scmManagerMock));
		Map<String, Object> actual = parseActualYaml();

		Map<String, Object> key = (Map<String, Object>) actual.get("key");
		assertThat(key.get("some")).isEqualTo("thing");
		assertThat(key.get("one")).isEqualTo(1);

		Map<String, Object> actualPrometheus = (Map<String, Object>) actual.get("prometheus");
		Map<String, Object> actualPrometheusSpec = (Map<String, Object>) actualPrometheus.get("prometheusSpec");
		assertThat(actualPrometheusSpec.get("scrapeConfigSelectorNilUsesHelmValues")).isEqualTo(null);
	}

	@Test
	void serviceMonitorSelectors() throws GitAPIException, IOException {
		config.getApplication().setNamePrefix("test1-");
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getSecrets().setActive(true);
		config.getFeatures().getIngress().setActive(false);

		LinkedHashSet<String> namespaceList = new LinkedHashSet<>(List.of(
			"test1-argocd",
			"test1-monitoring",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-secrets"
		));
		config.getApplication().getNamespaces().setDedicatedNamespaces(namespaceList);

		install(createStack(scmManagerMock));
		Map<String, Object> actual = parseActualYaml();

		Map<String, Object> expectedSelector = YAML_MAPPER.readValue(
			"""
				matchExpressions:
				  - key: kubernetes.io/metadata.name
				    operator: In
				    values:
				      - test1-argocd
				      - test1-monitoring
				      - test1-example-apps-staging
				      - test1-example-apps-production
				      - test1-secrets
				""", YAML_MAP_TYPE
		);

		Map<String, Object> prometheus = (Map<String, Object>) actual.get("prometheus");
		Map<String, Object> prometheusSpec = (Map<String, Object>) prometheus.get("prometheusSpec");
		assertThat(prometheusSpec.get("serviceMonitorNamespaceSelector")).isEqualTo(expectedSelector);
	}

	private Monitoring createStack(ScmManagerProviderMock scmManagerMock) throws GitAPIException {
		when(gitHandler.getResourcesScm()).thenReturn(scmManagerMock);

		TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, new FileSystemUtils()) {
			@Override
			public GitRepo create(String repoTarget, GitProvider scm) {
				GitRepo repo = super.create(repoTarget, scmManagerMock);
				clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir());

				File dashboardDir = new File(clusterResourcesRepoDir, "apps/monitoring/misc/dashboard");
				dashboardDir.mkdirs();

				try {
					Files.writeString(new File(dashboardDir, "traefik-dashboard.yaml").toPath(), "dummy");
					Files.writeString(
						new File(dashboardDir, "traefik-dashboard-requests-handling.yaml").toPath(),
						"dummy"
					);
					Files.writeString(new File(dashboardDir, "jenkins-dashboard.yaml").toPath(), "dummy");
					Files.writeString(new File(dashboardDir, "scmm-dashboard.yaml").toPath(), "dummy");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				return repo;
			}
		};

		GitRepo clusterResourcesRepo = repoProvider.create("argocd/cluster-resources", scmManagerMock);

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo));
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString());

		return new Monitoring(
			new FileSystemUtils() {
				@Override
				public Path writeTempFile(Map<String, Object> mapValues) {
					Path ret = super.writeTempFile(mapValues);
					temporaryYamlFilePrometheus = Path.of(ret.toString().replace(".ftl", ""));
					return ret;
				}
			},
			deployer,
			k8sClient,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator,
			new MonitoringToolConfigMapper(config)
		);
	}

	private boolean install(Monitoring monitoring) {
		deploymentContext = new ContextBuilder(config).build();
		return monitoring.execute(deploymentContext, repositoryWorkspace);
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YAML_MAPPER.readValue(temporaryYamlFilePrometheus.toFile(), YAML_MAP_TYPE);
	}
}
