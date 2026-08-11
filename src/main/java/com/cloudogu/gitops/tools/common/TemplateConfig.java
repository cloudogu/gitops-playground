package com.cloudogu.gitops.tools.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
		return immutableCopy(values);
	}

	private static Map<String, Object> immutableCopy(Map<String, Object> source) {
		Map<String, Object> copy = new HashMap<>();
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Map<?, ?> nested) {
				value = immutableCopy((Map<String, Object>) nested);
			}
			copy.put(entry.getKey(), value);
		}
		return Collections.unmodifiableMap(copy);
	}
}
