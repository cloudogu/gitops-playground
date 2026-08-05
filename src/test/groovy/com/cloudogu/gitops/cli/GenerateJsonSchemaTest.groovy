package com.cloudogu.gitops.cli

import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class GenerateJsonSchemaTest {

	@Test
	void 'generates documentation for enum fields without reflecting into Enum internals'() {
		assertThat(GenerateJsonSchema.generateDocs())
			.contains('| `scm.scmProviderType` | ScmProviderType | `SCM_MANAGER` |')
	}
}
