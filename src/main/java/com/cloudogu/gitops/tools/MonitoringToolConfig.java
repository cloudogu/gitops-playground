package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import lombok.Builder;

import java.util.Collection;
import java.util.List;
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
	Map<String, Object> templateConfig) {

	public MonitoringToolConfig {
		activeNamespaces = activeNamespaces == null ? List.of() : List.copyOf(activeNamespaces);
		templateConfig = templateConfig == null ? Map.of() : Map.copyOf(templateConfig);
	}
}
