package com.cloudogu.gitops.config.schema;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaGeneratorTest {

	@Test
	void configurationSchemaIsNotOutOfDate() throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode expected = new JsonSchemaGenerator().createSchema();
		JsonNode actual = objectMapper.readTree(
			new File(System.getProperty("user.dir"), "docs/configuration.schema.json")
		);

		assertThat(actual)
			.as("Config in docs/configuration.schema.json must be updated. Run GenerateJsonSchema class.")
			.isEqualTo(expected);
	}
}
