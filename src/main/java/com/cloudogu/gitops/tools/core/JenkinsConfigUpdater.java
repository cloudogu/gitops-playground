package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.config.Config;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class JenkinsConfigUpdater {

	private final Config config;

	public void updateUrl(String url) {
		config.getJenkins().setUrl(url);
	}
}
