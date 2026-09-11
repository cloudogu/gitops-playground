package com.cloudogu.gitops.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionTest {

	@Test
	void loadsGeneratedVersionName() {
		assertThat(Version.NAME)
			.isNotBlank()
			.doesNotContain("${")
			.contains("Copyright 2020 - present Cloudogu GmbH")
			.contains("GNU AFFERO GENERAL PUBLIC LICENSE, Version 3");
	}
}
