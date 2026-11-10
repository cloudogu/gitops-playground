package com.cloudogu.gitops.tools.externalsecrets;

import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record ExternalSecretsOperatorToolConfig(
	boolean active,
	String namespace,
	boolean operator,
	boolean skipCrds,
	boolean netpols,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	ExternalVaultConfig externalVault,
	List<ManagedExternalSecretConfig> managedSecrets,
	Map<String, Object> templateConfig
) {

	public ExternalSecretsOperatorToolConfig {
		managedSecrets = ImmutableConfigData.copyList(managedSecrets);
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
	}
}
