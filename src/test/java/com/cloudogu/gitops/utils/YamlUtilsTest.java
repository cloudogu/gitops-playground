package com.cloudogu.gitops.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlUtilsTest {

	@Test
	void parsesYamlMapWithoutGroovyRuntimeParser() {
		Map<String, Object> result = YamlUtils.parseYamlMap("""
			name: gop
			nested:
			  enabled: true
			""");

		assertThat(result.get("name")).isEqualTo("gop");
		assertThat(result.get("nested")).isEqualTo(Map.of("enabled", true));
	}

	@Test
	void rejectsYamlWithNonMapRoot() {
		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class, () ->
				YamlUtils.parseYamlMap("""
					- one
					- two
					""")
		);

		assertThat(exception.getMessage()).isEqualTo("Could not parse YAML as map: [one, two]");
	}
}
