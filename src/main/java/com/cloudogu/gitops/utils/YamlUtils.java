package com.cloudogu.gitops.utils;

import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.Map;

public final class YamlUtils {

	private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder().build();

	private YamlUtils() {
	}

	public static Map<String, Object> parseYamlMap(String yaml) {
		Object parsedYaml = YAML_MAPPER.readValue(yaml, Object.class);
		if (!(parsedYaml instanceof Map<?, ?>)) {
			throw new IllegalArgumentException("Could not parse YAML as map: " + parsedYaml);
		}
		return MapUtils.asStringObjectMap(parsedYaml);
	}
}
