package com.cloudogu.gitops.tools.common;

import lombok.Builder;

@Builder
public record ImagePullSecretConfig(
	boolean create,
	String proxyUrl,
	String url,
	String proxyUsername,
	String readOnlyUsername,
	String username,
	String proxyPassword,
	String readOnlyPassword,
	String password
) {
}
