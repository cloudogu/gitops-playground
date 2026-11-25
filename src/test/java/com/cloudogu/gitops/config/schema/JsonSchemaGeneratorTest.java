package com.cloudogu.gitops.config.schema;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaGeneratorTest {

	@Test
	void configurationSchemaIsNotOutOfDate() throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		String expected = objectMapper.writeValueAsString(
			objectMapper.readTree(new JsonSchemaGenerator().createSchema().toString())
		);
		String actual = objectMapper.writeValueAsString(
			objectMapper.readTree(new File(System.getProperty("user.dir"), "docs/configuration.schema.json"))
		);

		assertThat(actual)
			.as("Config in docs/configuration.schema.json must be updated. Run GenerateJsonSchema class.")
			.isEqualTo(expected);
	}

	@Test
	void usesDraft2020AndForbidsAdditionalRootProperties() {
		ObjectNode schema = new JsonSchemaGenerator().createSchema();

		assertThat(schema.at("/$schema").toString()).isEqualTo("\"https://json-schema.org/draft/2020-12/schema\"");
		assertThat(schema.at("/additionalProperties").toString()).isEqualTo("false");
	}

	@Test
	void makesFieldsNullable() {
		ObjectNode schema = new JsonSchemaGenerator().createSchema();

		assertThat(schema.at("/properties/registry/properties/active/type").toString()).isEqualTo("[\"boolean\",\"null\"]");
	}

	@Test
	void takesFieldDescriptionFromAnnotation() {
		ObjectNode schema = new JsonSchemaGenerator().createSchema();

		assertThat(schema.at("/properties/registry/properties/active/description").toString()).contains("cluster-local registry");
	}

	@Test
	void excludesFieldsWithoutDescriptionAnnotation() {
		ObjectNode schema = new JsonSchemaGenerator().createSchema();

		assertThat(schema.at("/properties/application/properties/namePrefixForEnvVars").isMissingNode()).isTrue();
	}

	@Test
	void allowsAdditionalPropertiesForMaps() {
		ObjectNode schema = new JsonSchemaGenerator().createSchema();

		assertThat(schema.at("/$defs/Map(String,String)/additionalProperties/type").toString()).isEqualTo("\"string\"");
	}
}
