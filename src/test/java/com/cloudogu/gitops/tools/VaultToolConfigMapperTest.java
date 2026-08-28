package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaultToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = config();
		config.getFeatures().getSecrets().getVault().setMode(Config.VaultMode.PROD);

		VaultToolConfig actual = new VaultToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(VaultToolConfig.builder()
													.active(true)
													.namespace("test-secrets")
													.namePrefix("test-")
													.url("https://vault.example.org")
													.developmentMode(false)
													.helm(HelmChartConfig.builder()
																		 .repoURL("https://vault-chart.example.org")
																		 .chart("vault-chart")
																		 .version("5.6.7")
																		 .values(Map.of("ha", true))
																		 .localHelmChartFolder("/charts")
																		 .build())
													.imagePullSecret(imagePullSecret())
													.templateConfig(Map.of(
														"application", Map.of(
															"namePrefix", "test-",
															"namespaceIsolation", true,
															"openshift", true,
															"password", "application-password",
															"podResources", true,
															"username", "application-user"
														),
														"features", Map.of(
															"argocd", Map.of("active", true),
															"certManager", Map.of(
																"active", true,
																"issuer", "production-issuer"
															),
															"secrets", Map.of(
																"vault", Map.of(
																	"oidc", Map.of(
																		"providerName",
																		"Keycloak",
																		"issuerUrl",
																		"",
																		"clientId",
																		"vault-client",
																		"clientSecret",
																		"",
																		"scopes",
																		java.util.List.of("openid", "profile", "email"),
																		"adminGroupName",
																		"",
																		"enabled",
																		false
																	),
																	"helm", Map.of("image", "vault-image")
																)
															)
														),
														"registry", Map.of("createImagePullSecrets", true)
													))
													.build());
	}

	@ParameterizedTest
	@CsvSource({
		"DEV, true",
		"PROD, false"
	})
	void mapsVaultModeToDevelopmentMode(Config.VaultMode mode, boolean expectedDevelopmentMode) {
		Config config = config();
		config.getFeatures().getSecrets().getVault().setMode(mode);

		VaultToolConfig actual = new VaultToolConfigMapper(config).map(context());

		assertThat(actual.developmentMode()).isEqualTo(expectedDevelopmentMode);
	}

	private static Config config() {
		Config config = new Config();

		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().setNamespaceIsolation(true);
		// Intentionally differs from the DeploymentContext to verify derived values come from the context.
		config.getApplication().setOpenshift(false);
		config.getApplication().setPassword("application-password");
		config.getApplication().setPodResources(true);
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

		config.getFeatures().getArgocd().setActive(true);

		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setIssuer("production-issuer");

		config.getFeatures().getSecrets().setActive(true);
		config.getFeatures().getSecrets().setNamespace("secrets");
		config.getFeatures().getSecrets().getVault().setUrl("https://vault.example.org");
		config.getFeatures().getSecrets().getVault().getOidc().setClientId("vault-client");

		config.getFeatures().getSecrets().getVault().getHelm().setRepoURL("https://vault-chart.example.org");
		config.getFeatures().getSecrets().getVault().getHelm().setChart("vault-chart");
		config.getFeatures().getSecrets().getVault().getHelm().setVersion("5.6.7");
		config.getFeatures().getSecrets().getVault().getHelm().setValues(Map.of("ha", true));
		config.getFeatures().getSecrets().getVault().getHelm().setImage("vault-image");

		return config;
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.OPENSHIFT
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
