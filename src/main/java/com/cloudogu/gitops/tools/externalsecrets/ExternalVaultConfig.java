package com.cloudogu.gitops.tools.externalsecrets;

import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.List;

@Builder
public record ExternalVaultConfig(
	String storeName,
	String server,
	String path,
	String version,
	CaProvider caProvider,
	String tokenSecretName,
	String tokenSecretKey,
	List<String> targetNamespaces
) {
	public ExternalVaultConfig {
		targetNamespaces = ImmutableConfigData.copyList(targetNamespaces);
	}

	public record CaProvider(String type, String name, String key) {
	}
}
