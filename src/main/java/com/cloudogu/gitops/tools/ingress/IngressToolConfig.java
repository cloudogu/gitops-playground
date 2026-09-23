package com.cloudogu.gitops.tools.ingress;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.Map;

@Builder
public record IngressToolConfig(
	boolean active,
	String namespace,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	boolean netpols,
	boolean monitoringActive,
	String monitoringNamespace,
	Map<String, Object> templateConfig
) {

	public IngressToolConfig {
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
	}
}
