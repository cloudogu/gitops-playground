package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.config.Config;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class ScmManagerConfigUpdater {

	private final Config config;

	public void updateNamespace(String namespace) {
		config.getScm().getScmManager().setNamespace(namespace);
	}
}
