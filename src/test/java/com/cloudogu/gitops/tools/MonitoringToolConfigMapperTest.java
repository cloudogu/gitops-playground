package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().getNamespaces().setDedicatedNamespaces(new LinkedHashSet<>(List.of("jenkins", "monitoring")));
		config.getApplication().getNamespaces().setTenantNamespaces(new LinkedHashSet<>(List.of("team-a", "team-b")));
		config.getApplication().setNamespaceIsolation(true);
		config.getApplication().setNetpols(true);
		config.getApplication().setSkipCrds(true);
		// Intentionally differs from the DeploymentContext to verify derived values come from the context.
		config.getApplication().setOpenshift(false);
		config.getApplication().setPodResources(true);
		config.getApplication().setPassword("application-password");
		config.getApplication().setUsername("application-user");
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy.example.org");
		config.getRegistry().setUrl("registry.example.org");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setReadOnlyUsername("read-only-user");
		config.getRegistry().setUsername("registry-user");
		config.getRegistry().setProxyPassword("proxy-password");
		config.getRegistry().setReadOnlyPassword("read-only-password");
		config.getRegistry().setPassword("registry-password");
		config.getJenkins().setActive(true);
		config.getJenkins().setInternal(false);
		config.getJenkins().setNamespace("jenkins-system");
		config.getJenkins().setUrl("https://jenkins.example.org");
		config.getJenkins().setMetricsUsername("jenkins-metrics-user");
		config.getJenkins().setMetricsPassword("jenkins-metrics-password");
		config.getFeatures().getIngress().setActive(true);
		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setIssuer("production-issuer");
		config.getFeatures().getMail().setActive(true);
		config.getFeatures().getMail().setSmtpAddress("smtp.example.org");
		config.getFeatures().getMail().setSmtpPort(2525);
		config.getFeatures().getMail().setSmtpUser("smtp-user");
		config.getFeatures().getMail().setSmtpPassword("smtp-password");
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getMonitoring().setNamespace("observability");
		config.getFeatures().getMonitoring().setGrafanaUrl("https://grafana.example.org");
		config.getFeatures().getMonitoring().setGrafanaEmailFrom("grafana@example.org");
		config.getFeatures().getMonitoring().setGrafanaEmailTo("team@example.org");
		config.getFeatures().getMonitoring().getOidc().setClientId("grafana-client");
		config.getFeatures().getMonitoring().getHelm().setRepoURL("https://monitoring.example.org");
		config.getFeatures().getMonitoring().getHelm().setChart("monitoring-chart");
		config.getFeatures().getMonitoring().getHelm().setVersion("6.7.8");
		config.getFeatures().getMonitoring().getHelm().setValues(Map.of("retention", "30d"));
		config.getFeatures().getMonitoring().getHelm().setGrafanaImage("grafana-image");
		config.getFeatures().getMonitoring().getHelm().setGrafanaSidecarImage("sidecar-image");
		config.getFeatures().getMonitoring().getHelm().setPrometheusImage("prometheus-image");
		config.getFeatures().getMonitoring().getHelm().setPrometheusOperatorImage("operator-image");
		config.getFeatures().getMonitoring().getHelm().setPrometheusConfigReloaderImage("reloader-image");
		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setNamespace("source-control");
		config.getScm().setScmManager(scmManager);

		MonitoringToolConfig actual = new MonitoringToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(MonitoringToolConfig.builder()
			.active(true)
			.namespace("test-observability")
			.namePrefix("test-")
			.activeNamespaces(List.of("jenkins", "monitoring", "team-a", "team-b"))
			.namespaceIsolation(true)
			.netpols(true)
			.skipCrds(true)
			.openshift(true)
			.airgapped(true)
			.applicationPassword("application-password")
			.jenkinsMetricsPassword("jenkins-metrics-password")
			.smtpUser("smtp-user")
			.smtpPassword("smtp-password")
			.grafanaUrl("https://grafana.example.org")
			.jenkinsInternal(false)
			.jenkinsNamespace("jenkins-system")
			.jenkinsUrl("https://jenkins.example.org")
			.jenkinsMetricsUsername("jenkins-metrics-user")
			.ingressActive(true)
			.jenkinsActive(true)
			.helm(HelmChartConfig.builder()
				.repoURL("https://monitoring.example.org")
				.chart("monitoring-chart")
				.version("6.7.8")
				.values(Map.of("retention", "30d"))
				.localHelmChartFolder("/charts")
				.build())
			.imagePullSecret(imagePullSecret())
			.templateConfig(Map.of(
				"application", Map.of(
					"namePrefix", "test-",
					"namespaceIsolation", true,
					"openshift", true,
					"podResources", true,
					"skipCrds", true,
					"password", "application-password",
					"username", "application-user"),
				"features", Map.of(
					"certManager", Map.of("active", true, "issuer", "production-issuer"),
					"mail", Map.of(
						"active", true,
						"smtpAddress", "smtp.example.org",
						"smtpPassword", "smtp-password",
						"smtpPort", 2525,
						"smtpUser", "smtp-user"),
					"monitoring", Map.of(
						"grafanaEmailFrom", "grafana@example.org",
						"grafanaEmailTo", "team@example.org",
						"grafanaUrl", "https://grafana.example.org",
						"namespace", "observability",
						"oidc", Map.of(
							"providerName", "Keycloak",
							"issuerUrl", "",
							"clientId", "grafana-client",
							"clientSecret", "",
							"scopes", List.of("openid", "profile", "email"),
							"adminGroupName", "",
							"enabled", false),
						"helm", Map.of(
							"grafanaImage", "grafana-image",
							"grafanaSidecarImage", "sidecar-image",
							"prometheusConfigReloaderImage", "reloader-image",
							"prometheusImage", "prometheus-image",
							"prometheusOperatorImage", "operator-image"))),
				"jenkins", Map.of("active", true),
				"registry", Map.of("createImagePullSecrets", true),
				"scm", Map.of(
					"scmManager", Map.of("namespace", "source-control"),
					"scmProviderType", ScmProviderType.SCM_MANAGER)))
			.build());
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.MULTI_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.INTERNAL,
			true,
			DeploymentContext.ClusterDistribution.OPENSHIFT);
	}

	private static ImagePullSecretConfig imagePullSecret() {
		return ImagePullSecretConfig.builder()
			.create(true)
			.proxyUrl("proxy.example.org")
			.url("registry.example.org")
			.proxyUsername("proxy-user")
			.readOnlyUsername("read-only-user")
			.username("registry-user")
			.proxyPassword("proxy-password")
			.readOnlyPassword("read-only-password")
			.password("registry-password")
			.build();
	}
}
