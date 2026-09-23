package com.cloudogu.gitops.tools.jenkins;

import com.cloudogu.gitops.application.credentials.CredentialsReference;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record JenkinsToolConfig(
	boolean active,
	boolean internal,
	String namespace,
	Application application,
	Server server,
	Scm scm,
	Registry registry,
	boolean argocdActive,
	boolean monitoringActive,
	String monitoringNamespace,
	boolean netpols,
	List<String> bootstrapCidrs,
	boolean ingressActive,
	String ingressNamespace,
	String kubernetesVersion,
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig
) {

	public JenkinsToolConfig {
		bootstrapCidrs = List.copyOf(bootstrapCidrs);
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
	}

	@Builder
	public record Application(
		String namePrefix,
		String environmentPrefix,
		boolean runningInsideK8s,
		boolean trace,
		boolean insecure
	) {
	}

	@Builder
	public record Server(
		String url,
		String username,
		String password,
		CredentialsReference credentials,
		String metricsUsername,
		String metricsPassword,
		CredentialsReference metricsCredentials,
		boolean skipRestart,
		boolean skipPlugins,
		String mavenCentralMirror,
		String internalBashImage,
		boolean oidcConfigured,
		Map<String, String> additionalEnvironments
	) {

		public Server {
			additionalEnvironments = ImmutableConfigData.copyMap(additionalEnvironments);
		}
	}

	@Builder
	public record Scm(
		ScmProviderType providerType
	) {
	}

	@Builder
	public record Registry(
		String url,
		String path,
		String username,
		String password,
		CredentialsReference credentials,
		boolean twoRegistries,
		String proxyUrl,
		String proxyPath,
		String proxyUsername,
		String proxyPassword,
		CredentialsReference proxyCredentials
	) {
	}
}
