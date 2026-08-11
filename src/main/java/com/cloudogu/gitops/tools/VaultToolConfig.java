package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import lombok.Builder;

import java.util.Map;

@Builder
public record VaultToolConfig(
	boolean active,
	String namespace,
	String namePrefix,
	String url,
	Config.VaultMode mode,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig) {

	public VaultToolConfig {
		templateConfig = templateConfig == null ? Map.of() : Map.copyOf(templateConfig);
	}
}
