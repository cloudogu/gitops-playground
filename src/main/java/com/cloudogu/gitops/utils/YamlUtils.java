package com.cloudogu.gitops.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

public final class YamlUtils {

	private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

	private YamlUtils() {
	}

	public static Map<String, Object> parseYamlMap(String yaml) {
		try {
			Object parsedYaml = YAML_MAPPER.readValue(yaml, Object.class);
			if (!(parsedYaml instanceof Map<?, ?>)) {
				throw new IllegalArgumentException("Could not parse YAML as map: " + parsedYaml);
			}
			return MapUtils.asStringObjectMap(parsedYaml);
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to parse YAML", exception);
		}
	}
}
