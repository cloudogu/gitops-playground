package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.credentials.CredentialsReference;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.Map;

@Builder
public record VaultToolConfig(
	boolean active,
	String namespace,
	String namePrefix,
	String url,
	String applicationUsername,
	String applicationPassword,
	CredentialsReference applicationCredentials,
	boolean developmentMode,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig
) {

	public VaultToolConfig {
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
	}
}
