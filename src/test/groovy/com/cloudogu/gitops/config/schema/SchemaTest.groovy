package com.cloudogu.gitops.config.schema

import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static com.cloudogu.gitops.config.schema.Schema.*

class SchemaTest {
    Schema testConfig = new Schema(registry: new RegistrySchema(
            twoRegistries: true,
            internalPort: 123))

    @Test
    void 'converts to yaml including internals'() {
        String config = testConfig.toYaml(true)

        assertThat(config).startsWith("""---
registry:
  internal: true
  twoRegistries: true
  internalPort: 123
""")
    }

    @Test
    void 'converts config map to yaml'() {

        String config = testConfig.toYaml(false)

        assertThat(config).startsWith("""---
registry:
  internalPort: 123
""")
    }
    
    @Test
    void 'creates from schema overwriting only Map values, ignoring null values'() {
        def expectedValues = new Schema(application: new ApplicationSchema(
                // Overwrites a default String
                username: 'myUser',
                // Overwrites a default Boolean
                yes: true,
                // Sets an otherwise empty string
                namePrefix: 'aPrefix'),
                // Overwrites a default Integer
                registry: new RegistrySchema(internalPort: 42))
        
        def actualValues = fromMap(expectedValues.toMap())
        
        assertThat(actualValues.application.username).isEqualTo(expectedValues.application.username)
        assertThat(actualValues.application.yes).isEqualTo(expectedValues.application.yes)
        assertThat(actualValues.application.namePrefix).isEqualTo(expectedValues.application.namePrefix)
        assertThat(actualValues.registry.internalPort).isEqualTo(expectedValues.registry.internalPort)
    }
}
