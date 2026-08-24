package com.cloudogu.gitops.tools.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImmutableConfigDataTest {

	@Test
	void createsADeepDefensiveCopyWhilePreservingNullValues() {
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("value", "before");
		List<Object> list = new ArrayList<>(List.of(nested));
		list.add(null);

		Map<String, Object> source = new LinkedHashMap<>();
		source.put("nullable", null);
		source.put("nested", nested);
		source.put("list", list);

		Map<String, Object> result = ImmutableConfigData.copyMap(source);

		nested.put("value", "after");
		list.add("later");
		source.put("additional", true);

		Map<String, Object> expectedNested = new LinkedHashMap<>();
		expectedNested.put("value", "before");
		List<Object> expectedList = new ArrayList<>();
		expectedList.add(expectedNested);
		expectedList.add(null);

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("nullable", null);
		expected.put("nested", expectedNested);
		expected.put("list", expectedList);

		assertThat(result).isEqualTo(expected);
		assertThatThrownBy(() -> result.put("other", "value"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> ((Map<String, Object>) result.get("nested")).put("other", "value"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> ((List<Object>) result.get("list")).add("value"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
