package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.credentials.CredentialsReference;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
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
	String applicationUsername,
	String applicationPassword,
	CredentialsReference applicationCredentials,
	String jenkinsMetricsUsername,
	String jenkinsMetricsPassword,
	CredentialsReference jenkinsMetricsCredentials,
	String smtpUser,
	String smtpPassword,
	CredentialsReference smtpCredentials,
	String grafanaUrl,
	boolean jenkinsInternal,
	String jenkinsNamespace,
	String jenkinsUrl,
	ScmProviderType scmProviderType,
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
