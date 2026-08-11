package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;

/**
 * Optional participation in the global configuration initialization lifecycle.
 *
 * <p>This lifecycle is separate from the tool deployment lifecycle. Implement this interface only
 * when a component needs access to the global {@link Config} before or after initialization.
 */
public interface ConfigLifecycleHook {

	default void preConfigInit(Config configToSet) {
	}

	default void postConfigInit(Config configToSet) {
	}
}
