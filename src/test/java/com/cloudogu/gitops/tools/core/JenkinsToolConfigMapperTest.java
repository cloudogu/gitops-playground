package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JenkinsToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setNamePrefixForEnvVars("TEST_");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().setRunningInsideK8s(true);
		config.getApplication().setTrace(true);
		config.getApplication().setInsecure(true);
		config.getApplication().setBaseUrl("example.org");
		config.getRegistry().setUrl("registry.example.org");
		config.getRegistry().setPath("images");
		config.getRegistry().setUsername("registry-user");
		config.getRegistry().setPassword("registry-password");
		config.getRegistry().setTwoRegistries(true);
		config.getRegistry().setProxyUrl("proxy.example.org");
		config.getRegistry().setProxyPath("proxy-images");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-password");
		config.getRegistry().setReadOnlyUsername("read-only-user");
		config.getRegistry().setReadOnlyPassword("read-only-password");
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getJenkins().setActive(true);
		config.getJenkins().setInternal(true);
		config.getJenkins().setNamespace("automation");
		config.getJenkins().setUrl("https://jenkins.example.org");
		config.getJenkins().setUsername("jenkins-user");
		config.getJenkins().setPassword("jenkins-password");
		config.getJenkins().setMetricsUsername("metrics-user");
		config.getJenkins().setMetricsPassword("metrics-password");
		config.getJenkins().setSkipRestart(true);
		config.getJenkins().setSkipPlugins(true);
		config.getJenkins().setMavenCentralMirror("https://maven.example.org");
		config.getJenkins().setInternalBashImage("bash:custom");
		config.getJenkins().setInternalDockerClientVersion("28.0.0");
		config.getJenkins().setJenkinsImage("jenkins:custom");
		config.getJenkins().setIngress("jenkins-ingress.example.org");
		config.getJenkins().setAdditionalEnvs(Map.of("FIRST", "one", "SECOND", "two"));
		config.getJenkins().getOidc().setIssuerUrl("https://id.example.org");
		config.getJenkins().getOidc().setClientId("jenkins-client");
		config.getJenkins().getOidc().setClientSecret("jenkins-client-secret");
		config.getJenkins().getHelm().setRepoURL("https://jenkins-chart.example.org");
		config.getJenkins().getHelm().setChart("jenkins-chart");
		config.getJenkins().getHelm().setVersion("7.8.9");
		config.getJenkins().getHelm().setValues(Map.of("controller", Map.of("replicas", 2)));
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setIssuer("production-issuer");
		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setPassword("scmm-password");
		config.getScm().setScmManager(scmManager);
		ScmTenantSchema.GitlabTenantConfig gitlab = new ScmTenantSchema.GitlabTenantConfig();
		gitlab.setUsername("gitlab-user");
		gitlab.setPassword("gitlab-password");
		config.getScm().setGitlab(gitlab);

		JenkinsToolConfig actual = new JenkinsToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(JenkinsToolConfig.builder()
			.active(true)
			.internal(true)
			.namespace("test-automation")
			.application(JenkinsToolConfig.Application.builder()
				.namePrefix("test-")
				.environmentPrefix("TEST_")
				.runningInsideK8s(true)
				.trace(true)
				.insecure(true)
				.build())
			.server(JenkinsToolConfig.Server.builder()
				.url("https://jenkins.example.org")
				.username("jenkins-user")
				.password("jenkins-password")
				.metricsUsername("metrics-user")
				.metricsPassword("metrics-password")
				.skipRestart(true)
				.skipPlugins(true)
				.mavenCentralMirror("https://maven.example.org")
				.internalBashImage("bash:custom")
				.oidcConfigured(true)
				.additionalEnvironments(Map.of("FIRST", "one", "SECOND", "two"))
				.build())
			.scm(JenkinsToolConfig.Scm.builder()
				.providerType(ScmProviderType.SCM_MANAGER)
				.scmManagerPassword("scmm-password")
				.gitlabUsername("gitlab-user")
				.gitlabPassword("gitlab-password")
				.build())
			.registry(JenkinsToolConfig.Registry.builder()
				.url("registry.example.org")
				.path("images")
				.username("registry-user")
				.password("registry-password")
				.twoRegistries(true)
				.proxyUrl("proxy.example.org")
				.proxyPath("proxy-images")
				.proxyUsername("proxy-user")
				.proxyPassword("proxy-password")
				.build())
			.argocdActive(true)
			.monitoringActive(true)
			.kubernetesVersion(Config.K8S_VERSION)
			.helm(HelmChartConfig.builder()
				.repoURL("https://jenkins-chart.example.org")
				.chart("jenkins-chart")
				.version("7.8.9")
				.values(Map.of("controller", Map.of("replicas", 2)))
				.localHelmChartFolder("/charts")
				.build())
			.imagePullSecret(ImagePullSecretConfig.builder()
				.create(true)
				.proxyUrl("proxy.example.org")
				.url("registry.example.org")
				.proxyUsername("proxy-user")
				.readOnlyUsername("read-only-user")
				.username("registry-user")
				.proxyPassword("proxy-password")
				.readOnlyPassword("read-only-password")
				.password("registry-password")
				.build())
			.templateConfig(Map.of(
				"application", Map.of("baseUrl", "example.org"),
				"features", Map.of("certManager", Map.of(
					"active", true,
					"issuer", "production-issuer")),
				"jenkins", Map.of(
					"helm", Map.of("version", "7.8.9"),
					"ingress", "jenkins-ingress.example.org",
					"internalBashImage", "bash:custom",
					"internalDockerClientVersion", "28.0.0",
					"jenkinsImage", "jenkins:custom",
					"oidc", Map.of(
						"providerName", "Keycloak",
						"issuerUrl", "https://id.example.org",
						"clientId", "jenkins-client",
						"clientSecret", "jenkins-client-secret",
						"scopes", List.of("openid", "profile", "email"),
						"adminGroupName", "",
						"enabled", true),
					"password", "jenkins-password",
					"url", "https://jenkins.example.org",
					"username", "jenkins-user"),
				"registry", Map.of("createImagePullSecrets", true)))
			.build());
	}

	@Test
	void doesNotExposeANamespaceForAnExternalJenkins() {
		Config config = new Config();
		config.getJenkins().setInternal(false);

		JenkinsToolConfig actual = new JenkinsToolConfigMapper(config).map(context());

		assertThat(actual.namespace()).isNull();
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES);
	}
}
