package com.cloudogu.gitops.tools.common

import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

class TemplateConfigTest {

	@Test
	void 'builds an immutable nested template view'() {
		Map<String, Object> result = new TemplateConfig()
			.put('application.namePrefix', 'test-')
			.put('application.optionalValue', null)
			.put('features.argocd.active', true)
			.values()

		assertThat(result).isEqualTo([
			application: [namePrefix: 'test-', optionalValue: null],
			features   : [argocd: [active: true]]
		])
		assertThatThrownBy { ((Map<String, Object>) result.application).put('other', true) }
			.isInstanceOf(UnsupportedOperationException)
	}
}
