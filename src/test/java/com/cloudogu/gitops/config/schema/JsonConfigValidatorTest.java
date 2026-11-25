package com.cloudogu.gitops.config.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonConfigValidatorTest {

	static Stream<Arguments> validSchemas() {
		return Stream.of(
			Arguments.of(
				"multiple values",
				Map.of("features", Map.of("argocd", Map.of("url", "http://localhost/argocd")))
			),
			Arguments.of(
				"internal SCM-Manager technical-user password",
				Map.of("scm", Map.of("scmManager", Map.of(
					"technicalUser", Map.of("password", "technical-password")
				)))
			),
			Arguments.of(
				"numeric value",
				Map.of("registry", Map.of("internalPort", 30000))
			),
			Arguments.of(
				"null value",
				Map.of("registry", Collections.singletonMap("internalPort", null))
			),
			Arguments.of(
				"free-form string map",
				Map.of("jenkins", Map.of("additionalEnvs", Map.of("FOO", "bar")))
			),
			Arguments.of(
				"enum value inside list entry",
				Map.of("content", Map.of("repos", List.of(Map.of("type", "COPY"))))
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("validSchemas")
	void testValidSchemas(String description, Map<?, ?> schema) {
		JsonSchemaValidator.validate(schema);
	}

	static Stream<Arguments> invalidSchemas() {
		return Stream.of(
			Arguments.of(
				"wrong type for registry.internalPort",
				Map.of("registry", Map.of("internalPort", "this should be a number"))
			),
			Arguments.of(
				"invalid additional key within registry",
				Map.of("registry", Map.of("url", "", "unexpectedKey", "this should error"))
			),
			Arguments.of(
				"invalid additional key on root level",
				Map.of(
					"registry", Map.of("url", ""),
					"unexpectedKey", "this should not exist"
				)
			),
			Arguments.of(
				"specifying dynamic value",
				Map.of(
					"application", Map.of(
						"namePrefix", "prefix",
						"namePrefixForEnvVars", "prefix"
					)
				)
			),
			Arguments.of(
				"obsolete internal SCM-Manager technical-user Secret reference",
				Map.of("scm", Map.of("scmManager", Map.of(
					"technicalUser", Map.of("credentials", Map.of(
						"secretName", "scm-technical-user-credentials",
						"secretNamespace", "gop-job"
					))
				)))
			),
			Arguments.of(
				"unknown enum value inside list entry",
				Map.of("content", Map.of("repos", List.of(Map.of("type", "UNKNOWN"))))
			),
			Arguments.of(
				"non-string value in string map",
				Map.of("jenkins", Map.of("additionalEnvs", Map.of("FOO", 1)))
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidSchemas")
	void testInvalidSchemas(String description, Map<?, ?> schema) {
		assertThrows(RuntimeException.class, () -> JsonSchemaValidator.validate(schema));
	}

	@Test
	void reportsOffendingKeyInErrorMessage() {
		Map<?, ?> schema = Map.of("registry", Map.of("unexpectedKey", "value"));

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.validate(schema));

		assertThat(exception.getMessage()).startsWith("Config file invalid: ").contains("unexpectedKey");
	}
}
