package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.assertj.core.api.Assertions.assertThat

class YamlUtilsTest {

	@Test
	void 'parses yaml map without groovy runtime parser'() {
		Map<String, Object> result = YamlUtils.parseYamlMap('''
name: gop
nested:
  enabled: true
''')

		assertThat(result.name).isEqualTo('gop')
		assertThat(result.nested).isEqualTo([enabled: true])
	}

	@Test
	void 'rejects yaml with non-map root'() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
			YamlUtils.parseYamlMap('''
- one
- two
''')
		}

		assertThat(exception.message).isEqualTo('Could not parse YAML as map: [one, two]')
	}
}
