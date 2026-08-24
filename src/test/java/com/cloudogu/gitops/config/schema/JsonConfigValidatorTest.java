package com.cloudogu.gitops.config.schema;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonConfigValidatorTest {

	static Stream<Arguments> validSchemas() {
		return Stream.of(
			Arguments.of(
				"multiple values",
				Map.of("features", Map.of("argocd", Map.of("url", "http://localhost/argocd")))
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
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidSchemas")
	void testInvalidSchemas(String description, Map<?, ?> schema) {
		assertThrows(RuntimeException.class, () -> JsonSchemaValidator.validate(schema));
	}
}
