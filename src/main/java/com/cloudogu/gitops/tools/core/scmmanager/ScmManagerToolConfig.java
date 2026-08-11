package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import lombok.Builder;

import java.util.Map;

@Builder
public record ScmManagerToolConfig(
	boolean active,
	boolean multiTenant,
	String namePrefix,
	String namespace,
	String releaseName,
	String ingress,
	String username,
	String password,
	String gitOpsUsername,
	boolean skipPlugins,
	boolean skipRestart,
	boolean jenkinsActive,
	String jenkinsUrl,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig) {

	public ScmManagerToolConfig {
		templateConfig = templateConfig == null ? Map.of() : Map.copyOf(templateConfig);
	}
}
