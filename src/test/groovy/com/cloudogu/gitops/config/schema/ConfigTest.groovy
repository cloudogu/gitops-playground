package com.cloudogu.gitops.config.schema

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.MapUtils
import org.junit.jupiter.api.Test
import picocli.CommandLine

import static com.cloudogu.gitops.config.Config.*
import static org.assertj.core.api.Assertions.assertThat

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

    @Test
    void 'parses secret credentials from CLI arguments'() {
        Config config = new Config()

        new CommandLine(config).parseArgs(
                '--jenkins-secret-name', 'jenkins-sec',
                '--jenkins-secret-namespace', 'jenkins-ns',
                '--registry-secret-name', 'reg-sec',
                '--registry-secret-namespace', 'reg-ns',
                '--secret-name', 'app-sec',
                '--secret-namespace', 'app-ns',
                '--smtp-secret-name', 'mail-sec',
                '--smtp-secret-namespace', 'mail-ns'
        )

        assertThat(config.jenkins.credentials.secretName).isEqualTo('jenkins-sec')
        assertThat(config.jenkins.credentials.secretNamespace).isEqualTo('jenkins-ns')
        assertThat(config.registry.credentials.secretName).isEqualTo('reg-sec')
        assertThat(config.registry.credentials.secretNamespace).isEqualTo('reg-ns')
        assertThat(config.application.credentials.secretName).isEqualTo('app-sec')
        assertThat(config.application.credentials.secretNamespace).isEqualTo('app-ns')
        assertThat(config.features.mail.credentials.secretName).isEqualTo('mail-sec')
        assertThat(config.features.mail.credentials.secretNamespace).isEqualTo('mail-ns')
    }

    @Test
    void 'parses credentials with secretName from map config'() {
        Config config = Config.fromMap([
                jenkins : [
                        credentials: [
                                secretName     : 'my-jenkins-secret',
                                secretNamespace: 'prod'
                        ]
                ],
                registry: [
                        credentials: [
                                secretName     : 'my-reg-secret',
                                secretNamespace: 'registry-ns'
                        ]
                ]
        ])

        assertThat(config.jenkins.credentials.secretName).isEqualTo('my-jenkins-secret')
        assertThat(config.jenkins.credentials.secretNamespace).isEqualTo('prod')
        assertThat(config.registry.credentials.secretName).isEqualTo('my-reg-secret')
        assertThat(config.registry.credentials.secretNamespace).isEqualTo('registry-ns')
    }
}