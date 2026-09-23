package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSecretsOperatorToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().setPodResources(true);
		config.getApplication().setSkipCrds(true);
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
		config.getFeatures().getSecrets().getExternalSecrets().setActive(true);
		config.getFeatures().getSecrets().setNamespace("external-secrets");
		// Intentionally differs from the DeploymentContext to verify the derived ArgoCD mode comes from the context.
		config.getFeatures().getArgocd().setOperator(false);
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setRepoURL("https://eso.example.org");
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setChart("eso-chart");
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setVersion("2.3.4");
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setValues(Map.of("replicas", 3));
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setImage("eso-image");
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setCertControllerImage("cert-controller-image");
		config.getFeatures().getSecrets().getExternalSecrets().getHelm().setWebhookImage("webhook-image");
		Config.SecretsSchema.ESOSchema.ExternalVaultSchema externalVault =
			config.getFeatures().getSecrets().getExternalSecrets().getVault();
		externalVault.setStoreName("customer-vault");
		externalVault.setServer("https://vault.example.org");
		externalVault.setPath("customer-secrets");
		externalVault.setVersion("v2");
		externalVault.getAuth().getTokenSecretRef().setName("vault-token");
		externalVault.getAuth().getTokenSecretRef().setKey("token");
		Config.SecretsSchema.ESOSchema.ExternalSecretSchema externalSecret =
			new Config.SecretsSchema.ESOSchema.ExternalSecretSchema();
		externalSecret.setName("customer-credentials");
		externalSecret.setNamespace("customer-app");
		externalSecret.setRemoteKey("gop/customer");
		externalSecret.setData(Map.of("username", "username", "password", "password"));
		config.getFeatures().getSecrets().getExternalSecrets().setSecrets(List.of(externalSecret));

		ExternalSecretsOperatorToolConfig actual = new ExternalSecretsOperatorToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(ExternalSecretsOperatorToolConfig.builder()
																	  .active(true)
																	  .namespace("test-external-secrets")
																	  .operator(true)
																	  .netpols(true)
																	  .skipCrds(true)
																	  .helm(HelmChartConfig.builder()
																						   .repoURL(
																							   "https://eso.example.org")
																						   .chart("eso-chart")
																						   .version("2.3.4")
																						   .values(Map.of(
																							   "replicas",
																							   3
																						   ))
																						   .localHelmChartFolder(
																							   "/charts")
																						   .build())
																	  .imagePullSecret(imagePullSecret())
																	  .externalVault(ExternalVaultConfig.builder()
																	    .storeName("customer-vault")
																	    .server("https://vault.example.org")
																	    .path("customer-secrets")
																	    .version("v2")
																	    .tokenSecretName("vault-token")
																	    .tokenSecretKey("token")
																	    .targetNamespaces(List.of("customer-app"))
																	    .build())
																	  .managedSecrets(List.of(ManagedExternalSecretConfig.builder()
																	    .name("customer-credentials")
																	    .namespace("customer-app")
																	    .remoteKey("gop/customer")
																	    .data(Map.of("username", "username", "password", "password"))
																	    .build()))
																	  .templateConfig(Map.of(
																		  "application",
																		  Map.of(
																			  "podResources",
																			  true,
																			  "skipCrds",
																			  true
																		  ),
																		  "features",
																		  Map.of(
																			  "secrets", Map.of(
																				  "externalSecrets", Map.of(
																					  "helm", Map.of(
																						  "image",
																						  "eso-image",
																						  "certControllerImage",
																						  "cert-controller-image",
																						  "webhookImage",
																						  "webhook-image"
																					  )
																				  )
																			  )
																		  ),
																		  "registry",
																		  Map.of("createImagePullSecrets", true)
																	  ))
																	  .build());
	}

	@Test
	void mapsExternalSecretsActiveIndependentlyFromAggregateSecretsFlag() {
		Config config = new Config();
		config.getFeatures().getSecrets().setActive(true);

		ExternalSecretsOperatorToolConfig actual = new ExternalSecretsOperatorToolConfigMapper(config).map(context());

		assertThat(actual.active()).isFalse();
	}

	@Test
	void rejectsManagedSecretsWithoutExternalVaultConnection() {
		Config config = new Config();
		Config.SecretsSchema.ESOSchema.ExternalSecretSchema externalSecret =
			new Config.SecretsSchema.ESOSchema.ExternalSecretSchema();
		externalSecret.setName("customer-credentials");
		externalSecret.setNamespace("customer-app");
		externalSecret.setRemoteKey("gop/customer");
		externalSecret.setData(Map.of("password", "password"));
		config.getFeatures().getSecrets().getExternalSecrets().setSecrets(List.of(externalSecret));

		IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
			IllegalArgumentException.class,
			() -> new ExternalSecretsOperatorToolConfigMapper(config).map(context())
		);

		assertThat(exception.getMessage())
			.isEqualTo("features.secrets.externalSecrets.vault.server must be configured when external Vault secrets are used");
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			DeploymentContext.ArgoCdDeploymentMode.OPERATOR,
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
