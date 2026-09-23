package com.cloudogu.gitops.config.schema;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.VaultMode;
import com.cloudogu.gitops.utils.MapUtils;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

	private final Config testConfig = createTestConfig();

	@Test
	void convertsToYamlIncludingInternals() {
		String config = testConfig.toYaml(true);

		assertThat(config).startsWith("---\nregistry:\n  internal: true\n");
	}

	@Test
	void convertsConfigMapToYaml() {
		String config = testConfig.toYaml(false);

		assertThat(config).startsWith("---\nregistry:\n  active: false\n");
	}

	@Test
	void createsFromSchemaOverwritingOnlyMapValuesIgnoringNullValues() {
		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setUsername("myUser");
		application.setYes(true);
		application.setNamePrefix("aPrefix");

		Config.RegistrySchema registry = new Config.RegistrySchema();
		registry.setInternalPort(42);

		Config expectedValues = new Config();
		expectedValues.setApplication(application);
		expectedValues.setRegistry(registry);

		Config actualValues = Config.fromMap(expectedValues.toMap());

		assertThat(actualValues.getApplication().getUsername()).isEqualTo(expectedValues.getApplication().getUsername());
		assertThat(actualValues.getApplication().getYes()).isEqualTo(expectedValues.getApplication().getYes());
		assertThat(actualValues.getApplication().getNamePrefix()).isEqualTo(expectedValues.getApplication().getNamePrefix());
		assertThat(actualValues.getRegistry().getInternalPort()).isEqualTo(expectedValues.getRegistry().getInternalPort());
	}

	@Test
	void parsesLowercaseVaultModeFromConfigAndPreservesExternalRepresentation() {
		Map<String, Object> input = Map.of(
			"features", Map.of(
				"secrets", Map.of(
					"vault", Map.of("mode", "dev")
				)
			)
		);
		Config config = Config.fromMap(input);

		assertThat(config.getFeatures().getSecrets().getVault().getMode()).isEqualTo(VaultMode.DEV);

		Map<String, Object> configMap = config.toMap();
		Map<String, Object> features = MapUtils.asStringObjectMap(configMap.get("features"));
		Map<String, Object> secrets = MapUtils.asStringObjectMap(features.get("secrets"));
		Map<String, Object> vault = MapUtils.asStringObjectMap(secrets.get("vault"));
		assertThat(vault.get("mode")).isEqualTo("dev");
	}

	@Test
	void mapsNetworkPolicyCidrsAndExternalConnections() {
		Config config = Config.fromMap(Map.of(
			"application", Map.of(
				"networkPolicies", Map.of(
					"bootstrapCidrs", List.of("172.18.0.1/32", "10.20.0.0/16"),
					"registryAccessCidrs", List.of("192.168.10.0/24"),
					"egressIsolation", true,
					"externalConnections", List.of(Map.of(
						"name", "external-scm-manager",
						"tool", "argocd-repo-server",
						"direction", "egress",
						"cidrs", List.of("35.246.133.109/32"),
						"ports", List.of(Map.of("protocol", "TCP", "port", 443))
					))
				)
			)
		));

		assertThat(config.getApplication().getNetworkPolicies().getBootstrapCidrs())
			.containsExactly("172.18.0.1/32", "10.20.0.0/16");
		assertThat(config.getApplication().getNetworkPolicies().getRegistryAccessCidrs())
			.containsExactly("192.168.10.0/24");
		assertThat(config.getApplication().getNetworkPolicies().isEgressIsolation()).isTrue();
		Config.ApplicationSchema.NetworkPoliciesSchema.ExternalConnectionSchema externalConnection =
			config.getApplication().getNetworkPolicies().getExternalConnections().getFirst();
		assertThat(externalConnection.getName()).isEqualTo("external-scm-manager");
		assertThat(externalConnection.getTool()).isEqualTo("argocd-repo-server");
		assertThat(externalConnection.getDirection()).isEqualTo("egress");
		assertThat(externalConnection.getCidrs()).containsExactly("35.246.133.109/32");
		assertThat(externalConnection.getPorts()).singleElement().satisfies(port -> {
			assertThat(port.getProtocol()).isEqualTo("TCP");
			assertThat(port.getPort()).isEqualTo(443);
		});
	}

	@Test
	void parsesLowercaseVaultModeFromCli() {
		Config config = new Config();

		new CommandLine(config).parseArgs("--vault=dev");

		assertThat(config.getFeatures().getSecrets().getVault().getMode()).isEqualTo(VaultMode.DEV);
	}

	@Test
	void getsTenantNameFromConfig() {
		testConfig.getApplication().setNamePrefix("testprefix-");

		assertThat(testConfig.getApplication().getTenantName()).isEqualTo("testprefix");
	}

	private static Config createTestConfig() {
		Config.RegistrySchema registry = new Config.RegistrySchema();
		registry.setTwoRegistries(true);
		registry.setInternalPort(123);

		Config config = new Config();
		config.setRegistry(registry);
		return config;
	}
}
