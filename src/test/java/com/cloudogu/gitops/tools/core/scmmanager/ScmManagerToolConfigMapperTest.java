package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScmManagerToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
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
		config.getJenkins().setUrlForScm("http://jenkins.automation.svc");
		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setIssuer("production-issuer");
		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setInternal(true);
		scmManager.setNamespace("source-control");
		scmManager.setIngress("scm.example.org");
		scmManager.setUsername("scm-user");
		scmManager.setPassword("scm-password");
		scmManager.setGitOpsUsername("gitops-user");
		scmManager.setSkipPlugins(true);
		scmManager.setSkipRestart(true);
		scmManager.setScmmImage("scm-manager:custom");
		scmManager.getHelm().setRepoURL("https://scm-chart.example.org");
		scmManager.getHelm().setChart("scm-chart");
		scmManager.getHelm().setVersion("8.9.10");
		scmManager.getHelm().setValues(Map.of("replicas", 2));
		config.getScm().setScmManager(scmManager);

		ScmManagerToolConfig actual = new ScmManagerToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(ScmManagerToolConfig.builder()
			.active(true)
			.multiTenant(true)
			.namePrefix("test-")
			.namespace("test-source-control")
			.releaseName("test-scmm")
			.ingress("scm.example.org")
			.username("scm-user")
			.password("scm-password")
			.gitOpsUsername("gitops-user")
			.skipPlugins(true)
			.skipRestart(true)
			.jenkinsActive(true)
			.jenkinsUrl("http://jenkins.automation.svc")
			.helm(HelmChartConfig.builder()
				.repoURL("https://scm-chart.example.org")
				.chart("scm-chart")
				.version("8.9.10")
				.values(Map.of("replicas", 2))
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
				"features", Map.of("certManager", Map.of(
					"active", true,
					"issuer", "production-issuer")),
				"registry", Map.of("createImagePullSecrets", true),
				"scm", Map.of("scmManager", Map.of("scmmImage", "scm-manager:custom"))))
			.build());
	}

	@Test
	void doesNotAddTheApplicationPrefixTwice() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setNamespace("test-source-control");
		config.getScm().setScmManager(scmManager);

		ScmManagerToolConfig actual = new ScmManagerToolConfigMapper(config).map(context());

		assertThat(actual.namespace()).isEqualTo("test-source-control");
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.MULTI_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.INTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES);
	}
}
