package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import lombok.Builder;

import java.util.Map;

@Builder
public record CertManagerToolConfig(
	boolean active,
	String namespace,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig) {

	public CertManagerToolConfig {
		templateConfig = templateConfig == null ? Map.of() : Map.copyOf(templateConfig);
	}
}
