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
}
