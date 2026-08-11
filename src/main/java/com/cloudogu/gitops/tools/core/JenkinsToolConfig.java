package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig;
import lombok.Builder;

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
	HelmChartConfig helm,
	ImagePullSecretConfig imagePullSecret,
	Map<String, Object> templateConfig) {

	public JenkinsToolConfig {
		templateConfig = templateConfig == null ? Map.of() : Map.copyOf(templateConfig);
	}

	@Builder
	public record Application(
		String namePrefix,
		String environmentPrefix,
		boolean runningInsideK8s,
		boolean trace,
		boolean insecure) {
	}

	@Builder
	public record Server(
		String url,
		String username,
		String password,
		String metricsUsername,
		String metricsPassword,
		boolean skipRestart,
		boolean skipPlugins,
		String mavenCentralMirror,
		String internalBashImage,
		boolean oidcConfigured,
		Map<String, String> additionalEnvironments) {

		public Server {
			additionalEnvironments = additionalEnvironments == null
				? Map.of()
				: Map.copyOf(additionalEnvironments);
		}
	}

	@Builder
	public record Scm(
		ScmProviderType providerType,
		String scmManagerPassword,
		String gitlabUsername,
		String gitlabPassword) {
	}

	@Builder
	public record Registry(
		String url,
		String path,
		String username,
		String password,
		boolean twoRegistries,
		String proxyUrl,
		String proxyPath,
		String proxyUsername,
		String proxyPassword) {
	}
}
