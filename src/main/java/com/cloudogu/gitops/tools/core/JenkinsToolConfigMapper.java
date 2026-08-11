package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class JenkinsToolConfigMapper implements ToolConfigMapper<JenkinsToolConfig> {

	@Override
	public JenkinsToolConfig map(DeploymentContext context) {
		Config config = context.getConfig();
		Config.JenkinsSchema jenkins = config.getJenkins();
		ScmProviderType scmProviderType = config.getScm() == null ? null : config.getScm().getScmProviderType();
		String scmManagerPassword = config.getScm() == null || config.getScm().getScmManager() == null
			? null
			: config.getScm().getScmManager().getPassword();
		String gitlabUsername = config.getScm() == null || config.getScm().getGitlab() == null
			? null
			: config.getScm().getGitlab().getUsername();
		String gitlabPassword = config.getScm() == null || config.getScm().getGitlab() == null
			? null
			: config.getScm().getGitlab().getPassword();
		Map<String, String> additionalEnvironments = jenkins.getAdditionalEnvs() == null
			? Map.of()
			: Map.copyOf(jenkins.getAdditionalEnvs());

		JenkinsToolConfig.Application applicationConfig = JenkinsToolConfig.Application.builder()
			.namePrefix(config.getApplication().getNamePrefix())
			.environmentPrefix(config.getApplication().getNamePrefixForEnvVars())
			.runningInsideK8s(config.getApplication().getRunningInsideK8s())
			.trace(config.getApplication().getTrace())
			.insecure(config.getApplication().getInsecure())
			.build();
		JenkinsToolConfig.Server serverConfig = JenkinsToolConfig.Server.builder()
			.url(jenkins.getUrl())
			.username(jenkins.getUsername())
			.password(jenkins.getPassword())
			.metricsUsername(jenkins.getMetricsUsername())
			.metricsPassword(jenkins.getMetricsPassword())
			.skipRestart(jenkins.getSkipRestart())
			.skipPlugins(jenkins.getSkipPlugins())
			.mavenCentralMirror(jenkins.getMavenCentralMirror())
			.internalBashImage(jenkins.getInternalBashImage())
			.oidcConfigured(jenkins.getOidc() != null && jenkins.getOidc().isEnabled())
			.additionalEnvironments(additionalEnvironments)
			.build();
		JenkinsToolConfig.Scm scmConfig = JenkinsToolConfig.Scm.builder()
			.providerType(scmProviderType)
			.scmManagerPassword(scmManagerPassword)
			.gitlabUsername(gitlabUsername)
			.gitlabPassword(gitlabPassword)
			.build();
		JenkinsToolConfig.Registry registryConfig = JenkinsToolConfig.Registry.builder()
			.url(config.getRegistry().getUrl())
			.path(config.getRegistry().getPath())
			.username(config.getRegistry().getUsername())
			.password(config.getRegistry().getPassword())
			.twoRegistries(config.getRegistry().getTwoRegistries())
			.proxyUrl(config.getRegistry().getProxyUrl())
			.proxyPath(config.getRegistry().getProxyPath())
			.proxyUsername(config.getRegistry().getProxyUsername())
			.proxyPassword(config.getRegistry().getProxyPassword())
			.build();

		return JenkinsToolConfig.builder()
			.active(jenkins.getActive())
			.internal(jenkins.getInternal())
			.namespace(jenkins.getInternal() ? config.getApplication().getNamePrefix() + jenkins.getNamespace() : null)
			.application(applicationConfig)
			.server(serverConfig)
			.scm(scmConfig)
			.registry(registryConfig)
			.argocdActive(config.getFeatures().getArgocd().getActive())
			.monitoringActive(config.getFeatures().getMonitoring().getActive())
			.helm(ToolConfigMapperSupport.helmChart(jenkins.getHelm(), config.getApplication().getLocalHelmChartFolder()))
			.imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
			.templateConfig(templateConfig(config))
			.build();
	}

	private static Map<String, Object> templateConfig(Config config) {
		return new TemplateConfig()
			.put("application.baseUrl", config.getApplication().getBaseUrl())
			.put("features.certManager.active", config.getFeatures().getCertManager().getActive())
			.put("features.certManager.issuer", config.getFeatures().getCertManager().getIssuer())
			.put("jenkins.helm.version", config.getJenkins().getHelm().getVersion())
			.put("jenkins.ingress", config.getJenkins().getIngress())
			.put("jenkins.internalBashImage", config.getJenkins().getInternalBashImage())
			.put("jenkins.internalDockerClientVersion", config.getJenkins().getInternalDockerClientVersion())
			.put("jenkins.jenkinsImage", config.getJenkins().getJenkinsImage())
			.put("jenkins.oidc", config.getJenkins().getOidc())
			.put("jenkins.password", config.getJenkins().getPassword())
			.put("jenkins.url", config.getJenkins().getUrl())
			.put("jenkins.username", config.getJenkins().getUsername())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.values();
	}
}
