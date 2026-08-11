package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import lombok.Builder;

@Builder
public record RegistryToolConfig(
	boolean active,
	boolean internal,
	String namespace,
	Integer internalPort,
	HelmChartConfig helm) {
}
