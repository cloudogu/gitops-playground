package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;

public interface ConfigLifecycleHook {

	default void preConfigInit(Config configToSet) {
	}

	default void postConfigInit(Config configToSet) {
	}
}
