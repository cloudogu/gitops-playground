package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;

public final class ToolConfigMapperSupport {

	private ToolConfigMapperSupport() {
	}

	public static HelmChartConfig helmChart(
		Config.HelmConfigWithValues helmConfig,
		String localHelmChartFolder) {
		return HelmChartConfig.builder()
			.repoURL(helmConfig.getRepoURL())
			.chart(helmConfig.getChart())
			.version(helmConfig.getVersion())
			.values(helmConfig.getValues())
			.localHelmChartFolder(localHelmChartFolder)
			.build();
	}

	public static ImagePullSecretConfig imagePullSecret(Config.RegistrySchema registry) {
		return ImagePullSecretConfig.builder()
			.create(registry.getCreateImagePullSecrets())
			.proxyUrl(registry.getProxyUrl())
			.url(registry.getUrl())
			.proxyUsername(registry.getProxyUsername())
			.readOnlyUsername(registry.getReadOnlyUsername())
			.username(registry.getUsername())
			.proxyPassword(registry.getProxyPassword())
			.readOnlyPassword(registry.getReadOnlyPassword())
			.password(registry.getPassword())
			.build();
	}
}
