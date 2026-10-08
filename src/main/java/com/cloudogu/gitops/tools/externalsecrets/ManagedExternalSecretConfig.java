package com.cloudogu.gitops.tools.externalsecrets;

import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.Map;

@Builder
public record ManagedExternalSecretConfig(
	String name,
	String namespace,
	String remoteKey,
	Map<String, String> data
) {
	public ManagedExternalSecretConfig {
		data = ImmutableConfigData.copyMap(data);
	}
}
