package com.cloudogu.gitops.tools.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateConfigTest {

	@Test
	void buildsAnImmutableNestedTemplateView() {
		Map<String, Object> result = new TemplateConfig()
			.put("application.namePrefix", "test-")
			.put("application.optionalValue", null)
			.put("features.argocd.active", true)
			.values();

		Map<String, Object> application = new LinkedHashMap<>();
		application.put("namePrefix", "test-");
		application.put("optionalValue", null);

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("application", application);
		expected.put("features", Map.of("argocd", Map.of("active", true)));

		assertThat(result).isEqualTo(expected);
		assertThatThrownBy(() -> ((Map<String, Object>) result.get("application")).put("other", true))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
