package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngressToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().setNetpols(true);
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy.example.org");
		config.getRegistry().setUrl("registry.example.org");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setReadOnlyUsername("read-only-user");
		config.getRegistry().setUsername("registry-user");
		config.getRegistry().setProxyPassword("proxy-password");
		config.getRegistry().setReadOnlyPassword("read-only-password");
		config.getRegistry().setPassword("registry-password");
		config.getFeatures().getIngress().setActive(true);
		config.getFeatures().getIngress().setIngressNamespace("gateway");
		config.getFeatures().getIngress().getHelm().setRepoURL("https://ingress.example.org");
		config.getFeatures().getIngress().getHelm().setChart("ingress-chart");
		config.getFeatures().getIngress().getHelm().setVersion("3.4.5");
		config.getFeatures().getIngress().getHelm().setValues(Map.of("replicas", 4));
		config.getFeatures().getIngress().getHelm().setImage("ingress-image");
		config.getFeatures().getMonitoring().setActive(true);
		config.getFeatures().getMonitoring().setNamespace("observability");

		IngressToolConfig actual = new IngressToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(IngressToolConfig.builder()
			.active(true)
			.namespace("test-gateway")
			.helm(HelmChartConfig.builder()
				.repoURL("https://ingress.example.org")
				.chart("ingress-chart")
				.version("3.4.5")
				.values(Map.of("replicas", 4))
				.localHelmChartFolder("/charts")
				.build())
			.imagePullSecret(imagePullSecret())
			.templateConfig(Map.of(
				"application", Map.of("namePrefix", "test-", "netpols", true),
				"features", Map.of(
					"ingress", Map.of("helm", Map.of("image", "ingress-image")),
					"monitoring", Map.of("active", true, "namespace", "observability")),
				"registry", Map.of("createImagePullSecrets", true)))
			.build());
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES);
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
