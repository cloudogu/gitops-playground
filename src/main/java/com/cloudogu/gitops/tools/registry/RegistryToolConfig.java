package com.cloudogu.gitops.tools.registry;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import lombok.Builder;

import java.util.List;

@Builder
public record RegistryToolConfig(
	boolean active,
	boolean internal,
	String namespace,
	int bootstrapNodePort,
	Integer internalPort,
	boolean netpols,
	List<String> registryAccessCidrs,
	HelmChartConfig helm
) {
}
