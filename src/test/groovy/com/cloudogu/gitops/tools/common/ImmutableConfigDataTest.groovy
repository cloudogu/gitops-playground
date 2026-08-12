package com.cloudogu.gitops.tools.common

import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

class ImmutableConfigDataTest {

	@Test
	void 'creates a deep defensive copy while preserving null values'() {
		Map<String, Object> nested = [value: 'before']
		List<Object> list = [nested, null]
		Map<String, Object> source = [nullable: null, nested: nested, list: list]

		Map<String, Object> result = ImmutableConfigData.copyMap(source)

		nested.value = 'after'
		list.add('later')
		source.additional = true

		assertThat(result).isEqualTo([
			nullable: null,
			nested  : [value: 'before'],
			list    : [[value: 'before'], null]
		])
		assertThatThrownBy { result.put('other', 'value') }
			.isInstanceOf(UnsupportedOperationException)
		assertThatThrownBy { ((Map<String, Object>) result.nested).put('other', 'value') }
			.isInstanceOf(UnsupportedOperationException)
		assertThatThrownBy { ((List<Object>) result.list).add('value') }
			.isInstanceOf(UnsupportedOperationException)
	}
}
