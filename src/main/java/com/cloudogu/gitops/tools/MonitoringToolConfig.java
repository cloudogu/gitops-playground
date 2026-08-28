package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.Collection;
import java.util.Map;

@Builder
public record MonitoringToolConfig(
	boolean active,
	String namespace,
	String namePrefix,
	Collection<String> activeNamespaces,
	boolean namespaceIsolation,
	boolean netpols,
	boolean skipCrds,
	boolean openshift,
	boolean airgapped,
	String applicationPassword,
	String jenkinsMetricsPassword,
	String smtpUser,
	String smtpPassword,
	String grafanaUrl,
	boolean jenkinsInternal,
	String jenkinsNamespace,
	String jenkinsUrl,
	String jenkinsMetricsUsername,
	boolean ingressActive,
	boolean jenkinsActive,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig
) {

	public MonitoringToolConfig {
		activeNamespaces = ImmutableConfigData.copyList(activeNamespaces);
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
	}
}
