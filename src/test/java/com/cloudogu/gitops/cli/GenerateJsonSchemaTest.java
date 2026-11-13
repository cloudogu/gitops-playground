package com.cloudogu.gitops.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateJsonSchemaTest {

	@Test
	void generatesDocumentationForEnumFieldsWithoutReflectingIntoEnumInternals() {
		assertThat(GenerateJsonSchema.generateDocs())
			.contains("| `scm.scmProviderType` | ScmProviderType | `SCM_MANAGER` |")
			.contains("`{}`")
			.doesNotContain("`[:]`");
	}

	@Test
	void documentsFieldsOfListEntries() {
		String documentation = GenerateJsonSchema.generateDocs();

		assertThat(documentation)
			.contains("`features.secrets.externalSecrets.secrets[].name`")
			.contains("`features.secrets.externalSecrets.secrets[].namespace`")
			.contains("`features.secrets.externalSecrets.secrets[].remoteKey`")
			.contains("`features.secrets.externalSecrets.secrets[].refreshInterval`")
			.contains("`features.secrets.externalSecrets.secrets[].data`")
			.contains("`application.networkPolicies.externalConnections[].ports[].port`")
			.contains("`content.repos[].url`");
	}
}
