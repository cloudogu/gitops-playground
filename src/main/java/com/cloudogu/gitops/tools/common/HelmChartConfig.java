package com.cloudogu.gitops.tools.common;

import lombok.Builder;

import java.util.Map;

@Builder
public record HelmChartConfig(
	String repoURL,
	String chart,
	String version,
	Map<String, Object> values,
	String localHelmChartFolder) {

	public HelmChartConfig {
		values = ImmutableConfigData.copyMap(values);
	}
}
