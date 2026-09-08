package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

	/**
	 * Projects the central OIDC schema into plain template data. This prevents tool DTOs from
	 * retaining central Config schema objects through their template view.
	 */
	public static Map<String, Object> oidc(Config.OidcSchema oidc) {
		if (oidc == null) {
			return Map.of();
		}

		return new TemplateConfig()
			.put("providerName", oidc.getProviderName())
			.put("issuerUrl", oidc.getIssuerUrl())
			.put("clientId", oidc.getClientId())
			.put("clientSecret", oidc.getClientSecret())
			.put("scopes", oidc.getScopes())
			.put("adminGroupName", oidc.getAdminGroupName())
			.put("enabled", oidc.isEnabled())
			.values();
	}

	/**
	 * Projects only the Helm repository URL needed by ArgoCD templates. This keeps the tool view
	 * focused and prevents central Config schema objects from crossing the DTO boundary.
	 */
	public static List<Map<String, Object>> helmReleaseRepositories(
		Collection<Config.ContentSchema.HelmReleaseSchema> helmReleases) {
		if (helmReleases == null || helmReleases.isEmpty()) {
			return List.of();
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (Config.ContentSchema.HelmReleaseSchema release : helmReleases) {
			if (release != null) {
				result.add(new TemplateConfig().put("repoURL", release.getRepoURL()).values());
			}
		}
		return ImmutableConfigData.copyList(result);
	}
}
