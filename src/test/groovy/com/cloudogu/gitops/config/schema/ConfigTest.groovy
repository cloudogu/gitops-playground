package com.cloudogu.gitops.config.schema

import static com.cloudogu.gitops.config.Config.*
import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.MapUtils

import org.junit.jupiter.api.Test
import picocli.CommandLine

class ConfigTest {
	Config testConfig = new Config(registry: new RegistrySchema(twoRegistries: true,
		internalPort: 123))

	@Test
	void 'converts to yaml including internals'() {
		String config = testConfig.toYaml(true)

		assertThat(config).startsWith("""---
registry:
  internal: true
""")
	}

	@Test
	void 'converts config map to yaml'() {

		String config = testConfig.toYaml(false)

		assertThat(config).startsWith("""---
registry:
  active: false
""")
	}

	@Test
	void 'creates from schema overwriting only Map values, ignoring null values'() {
		Config expectedValues = new Config(application: new ApplicationSchema(// Overwrites a default String
			username: 'myUser',
			// Overwrites a default Boolean
			yes: true,
			// Sets an otherwise empty string
			namePrefix: "aPrefix"),
			// Overwrites a default Integer
			registry: new RegistrySchema(internalPort: 42))

		def actualValues = fromMap(expectedValues.toMap())

		assertThat(actualValues.application.username).isEqualTo(expectedValues.application.username)
		assertThat(actualValues.application.yes).isEqualTo(expectedValues.application.yes)
		assertThat(actualValues.application.namePrefix).isEqualTo(expectedValues.application.namePrefix)
		assertThat(actualValues.registry.internalPort).isEqualTo(expectedValues.registry.internalPort)
	}

	@Test
	void 'parses lowercase vault mode from config and preserves external representation'() {
		Config config = Config.fromMap([features: [secrets: [vault: [mode: 'dev']]]])

		assertThat(config.features.secrets.vault.mode).isEqualTo(VaultMode.DEV)

		Map<String, Object> configMap = config.toMap()
		Map<String, Object> features = MapUtils.asStringObjectMap(configMap.get('features'))
		Map<String, Object> secrets = MapUtils.asStringObjectMap(features.get('secrets'))
		Map<String, Object> vault = MapUtils.asStringObjectMap(secrets.get('vault'))
		assertThat(vault.get('mode')).isEqualTo('dev')
	}

	@Test
	void 'parses lowercase vault mode from cli'() {
		Config config = new Config()

		new CommandLine(config).parseArgs('--vault=dev')

		assertThat(config.features.secrets.vault.mode).isEqualTo(VaultMode.DEV)
	}

	@Test
	void 'getting Tenantname from Config'() {
		testConfig.application.namePrefix = 'testprefix-'
		assertThat(testConfig.application.getTenantName()).isEqualTo("testprefix")
	}
}