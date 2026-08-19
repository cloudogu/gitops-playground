package com.cloudogu.gitops.tools.common;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds a focused template configuration so FreeMarker receives only
 * the values required by a template instead of the complete application Config.
 */
public final class TemplateConfig {

	private final Map<String, Object> values = new HashMap<>();

	public TemplateConfig put(String path, Object value) {
		String[] segments = path.split("\\.");
		Map<String, Object> current = values;
		for (int index = 0; index < segments.length - 1; index++) {
			Object nested = current.computeIfAbsent(segments[index], ignored -> new HashMap<String, Object>());
			current = (Map<String, Object>) nested;
		}
		current.put(segments[segments.length - 1], value);
		return this;
	}

	public Map<String, Object> values() {
		return ImmutableConfigData.copyMap(values);
	}
}
