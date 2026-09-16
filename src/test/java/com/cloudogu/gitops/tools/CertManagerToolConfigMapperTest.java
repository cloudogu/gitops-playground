package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CertManagerToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().setPodResources(true);
		config.getApplication().setSkipCrds(true);
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy.example.org");
		config.getRegistry().setUrl("registry.example.org");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setReadOnlyUsername("read-only-user");
		config.getRegistry().setUsername("registry-user");
		config.getRegistry().setProxyPassword("proxy-password");
		config.getRegistry().setReadOnlyPassword("read-only-password");
		config.getRegistry().setPassword("registry-password");
		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setNamespace("certificates");
		config.getFeatures().getCertManager().setIssuer("production-issuer");
		config.getFeatures().getCertManager().getHelm().setRepoURL("https://cert.example.org");
		config.getFeatures().getCertManager().getHelm().setChart("cert-chart");
		config.getFeatures().getCertManager().getHelm().setVersion("1.2.3");
		config.getFeatures().getCertManager().getHelm().setValues(Map.of("replicas", 2));
		config.getFeatures().getCertManager().getHelm().setImage("cert-image");
		config.getFeatures().getCertManager().getHelm().setWebhookImage("webhook-image");
		config.getFeatures().getCertManager().getHelm().setCainjectorImage("cainjector-image");
		config.getFeatures().getCertManager().getHelm().setAcmeSolverImage("solver-image");
		config.getFeatures().getCertManager().getHelm().setStartupAPICheckImage("startup-image");

		CertManagerToolConfig actual = new CertManagerToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(CertManagerToolConfig.builder()
														  .active(true)
														  .namespace("test-certificates")
														  .helm(HelmChartConfig.builder()
																			   .repoURL("https://cert.example.org")
																			   .chart("cert-chart")
																			   .version("1.2.3")
																			   .values(Map.of("replicas", 2))
																			   .localHelmChartFolder("/charts")
																			   .build())
														  .imagePullSecret(imagePullSecret())
														  .templateConfig(Map.of(
															  "application",
															  Map.of("podResources", true, "skipCrds", true),
															  "features",
															  Map.of(
																  "certManager", Map.of(
																	  "issuer", "production-issuer",
																	  "helm", Map.of(
																		  "image", "cert-image",
																		  "webhookImage", "webhook-image",
																		  "cainjectorImage", "cainjector-image",
																		  "acmeSolverImage", "solver-image",
																		  "startupAPICheckImage", "startup-image"
																	  )
																  )
															  ),
															  "registry",
															  Map.of("createImagePullSecrets", true)
														  ))
														  .build());
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES
		);
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
