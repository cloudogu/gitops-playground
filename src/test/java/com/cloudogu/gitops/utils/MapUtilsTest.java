package com.cloudogu.gitops.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapUtilsTest {

	@Test
	void mergesNestedImmutableMapsIntoMutableTarget() {
		// given
		Map<String, Object> originalValues = Map.of(
			"details", Map.of("existingSetting", true)
		);
		Map<String, Object> target = new HashMap<>(Map.of("nested", originalValues));
		Map<String, Object> overrides = Map.of(
			"nested", Map.of("details", Map.of("additionalSetting", false))
		);

		// when
		Map<String, Object> mergedValues = MapUtils.deepMerge(overrides, target);

		// then
		assertThat(mergedValues).isSameAs(target);
		assertThat(mergedValues).containsEntry("nested", Map.of(
			"details", Map.of("existingSetting", true, "additionalSetting", false)
		));
		assertThat(originalValues).containsEntry("details", Map.of("existingSetting", true));
	}
}
