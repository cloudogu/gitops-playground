package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record ScmManagerToolConfig(
	boolean active,
	boolean multiTenant,
	String namePrefix,
	String namespace,
	String releaseName,
	String ingress,
	String gitOpsUsername,
	boolean skipPlugins,
	boolean skipRestart,
	boolean netpols,
	List<String> bootstrapCidrs,
	boolean argocdActive,
	String argocdNamespace,
	boolean ingressActive,
	String ingressNamespace,
	boolean monitoringActive,
	String monitoringNamespace,
	boolean jenkinsActive,
	boolean jenkinsInternal,
	String jenkinsNamespace,
	String jenkinsUrl,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig
) {

	public ScmManagerToolConfig {
		bootstrapCidrs = List.copyOf(bootstrapCidrs);
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
	}
}
