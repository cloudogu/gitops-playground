package com.cloudogu.gitops.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DockerImageParserTest {

	@Test
	void parsesSimpleImageString() {
		DockerImageParser.Image result = DockerImageParser.parse("grafana/grafana:latest");

		assertThat(result.getRegistry()).isEqualTo("");
		assertThat(result.getRepository()).isEqualTo("grafana/grafana");
		assertThat(result.getRegistryAndRepositoryAsString()).isEqualTo("grafana/grafana");
		assertThat(result.getTag()).isEqualTo("latest");
	}

	@Test
	void parsesImageStringWithPort() {
		DockerImageParser.Image result = DockerImageParser.parse("localhost:5000/grafana/grafana:latest");

		assertThat(result.getRegistry()).isEqualTo("localhost:5000");
		assertThat(result.getRepository()).isEqualTo("grafana/grafana");
		assertThat(result.getRegistryAndRepositoryAsString()).isEqualTo("localhost:5000/grafana/grafana");
		assertThat(result.getTag()).isEqualTo("latest");
	}

	@Test
	void throwsWhenThereIsNoColon() {
		assertThrows(RuntimeException.class, () -> DockerImageParser.parse("grafana/grafana"));
	}
}
