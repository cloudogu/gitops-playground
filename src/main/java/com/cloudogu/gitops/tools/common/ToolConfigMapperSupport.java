package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.credentials.CredentialsReference;
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

	public static List<String> networkPolicyBootstrapCidrs(Config config) {
		Config.ApplicationSchema.NetworkPoliciesSchema networkPolicies = config.getApplication().getNetworkPolicies();
		return networkPolicies == null || networkPolicies.getBootstrapCidrs() == null
			? List.of()
			: List.copyOf(networkPolicies.getBootstrapCidrs());
	}

	public static List<String> networkPolicyRegistryAccessCidrs(Config config) {
		Config.ApplicationSchema.NetworkPoliciesSchema networkPolicies = config.getApplication().getNetworkPolicies();
		return networkPolicies == null || networkPolicies.getRegistryAccessCidrs() == null
			? List.of()
			: List.copyOf(networkPolicies.getRegistryAccessCidrs());
	}

	public static List<Map<String, Object>> networkPolicyExternalConnections(
		Config config,
		String tool,
		String direction) {
		Config.ApplicationSchema.NetworkPoliciesSchema networkPolicies = config.getApplication().getNetworkPolicies();
		if (networkPolicies == null || networkPolicies.getExternalConnections() == null) {
			return List.of();
		}

		return networkPolicies.getExternalConnections()
			.stream()
			.filter(connection -> connection != null)
			.filter(connection -> tool.equalsIgnoreCase(connection.getTool()))
			.filter(connection -> direction.equalsIgnoreCase(connection.getDirection()))
			.filter(ToolConfigMapperSupport::hasExternalConnectionRules)
			.map(ToolConfigMapperSupport::externalConnectionTemplateData)
			.toList();
	}

	private static boolean hasExternalConnectionRules(
		Config.ApplicationSchema.NetworkPoliciesSchema.ExternalConnectionSchema connection) {
		return connection.getCidrs() != null
			&& !connection.getCidrs().isEmpty()
			&& connection.getPorts() != null
			&& connection.getPorts().stream().anyMatch(port -> port != null && port.getPort() != null);
	}

	private static Map<String, Object> externalConnectionTemplateData(
		Config.ApplicationSchema.NetworkPoliciesSchema.ExternalConnectionSchema connection) {
		List<Map<String, Object>> ports = connection.getPorts() == null
			? List.of()
			: connection.getPorts()
				.stream()
				.filter(port -> port != null && port.getPort() != null)
				.map(port -> new TemplateConfig()
					.put("protocol", port.getProtocol() == null || port.getProtocol().isBlank() ? "TCP" : port.getProtocol().toUpperCase())
					.put("port", port.getPort())
					.values())
				.toList();

		return new TemplateConfig()
			.put("name", connection.getName())
			.put("cidrs", connection.getCidrs() == null ? List.of() : List.copyOf(connection.getCidrs()))
			.put("ports", ports)
			.values();
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
									.proxyCredentials(CredentialsReference.from(registry.getProxyCredentials()))
									.readOnlyCredentials(CredentialsReference.from(registry.getReadOnlyCredentials()))
									.credentials(CredentialsReference.from(registry.getCredentials()))
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
