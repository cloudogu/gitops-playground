package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.Map;

@Singleton
@RequiredArgsConstructor
public class MonitoringToolConfigMapper implements ToolConfigMapper<MonitoringToolConfig> {

	private final Config config;

	@Override
	public MonitoringToolConfig map(DeploymentContext context) {
		Config.MonitoringSchema monitoring = config.getFeatures().getMonitoring();
		Collection<String> activeNamespaces = config.getApplication().getNamespaces().getActiveNamespaces();
		return MonitoringToolConfig.builder()
								   .active(monitoring.getActive())
								   .namespace(config.getApplication().getNamePrefix() + monitoring.getNamespace())
								   .namePrefix(config.getApplication().getNamePrefix())
								   .activeNamespaces(activeNamespaces)
								   .namespaceIsolation(config.getApplication().getNamespaceIsolation())
								   .netpols(config.getApplication().getNetpols())
								   .skipCrds(config.getApplication().getSkipCrds())
								   .openshift(context.isOpenshift())
								   .airgapped(context.isAirgapped())
								   .applicationPassword(config.getApplication().getPassword())
								   .jenkinsMetricsPassword(config.getJenkins().getMetricsPassword())
								   .smtpUser(config.getFeatures().getMail().getSmtpUser())
								   .smtpPassword(config.getFeatures().getMail().getSmtpPassword())
								   .grafanaUrl(monitoring.getGrafanaUrl())
								   .jenkinsInternal(config.getJenkins().getInternal())
								   .jenkinsNamespace(config.getJenkins().getNamespace())
								   .jenkinsUrl(config.getJenkins().getUrl())
								   .jenkinsMetricsUsername(config.getJenkins().getMetricsUsername())
								   .ingressActive(config.getFeatures().getIngress().getActive())
								   .jenkinsActive(config.getJenkins().getActive())
								   .helm(ToolConfigMapperSupport.helmChart(
									   monitoring.getHelm(),
									   config.getApplication().getLocalHelmChartFolder()
								   ))
								   .imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
								   .templateConfig(templateConfig(config, context))
								   .build();
	}

	private static Map<String, Object> templateConfig(Config config, DeploymentContext context) {
		Config.MonitoringSchema.MonitoringHelmSchema helm = config.getFeatures().getMonitoring().getHelm();
		String scmManagerNamespace = config.getScm() == null || config.getScm().getScmManager() == null
			? "scm-manager"
			: config.getScm().getScmManager().getNamespace();
		return new TemplateConfig()
			.put("application.namePrefix", config.getApplication().getNamePrefix())
			.put("application.namespaceIsolation", config.getApplication().getNamespaceIsolation())
			.put("application.openshift", context.isOpenshift())
			.put("application.podResources", config.getApplication().getPodResources())
			.put("application.skipCrds", config.getApplication().getSkipCrds())
			.put("application.password", config.getApplication().getPassword())
			.put("application.username", config.getApplication().getUsername())
			.put("features.certManager.active", config.getFeatures().getCertManager().getActive())
			.put("features.certManager.issuer", config.getFeatures().getCertManager().getIssuer())
			.put("features.mail.active", config.getFeatures().getMail().getActive())
			.put("features.mail.smtpAddress", config.getFeatures().getMail().getSmtpAddress())
			.put("features.mail.smtpPassword", config.getFeatures().getMail().getSmtpPassword())
			.put("features.mail.smtpPort", config.getFeatures().getMail().getSmtpPort())
			.put("features.mail.smtpUser", config.getFeatures().getMail().getSmtpUser())
			.put("features.monitoring.grafanaEmailFrom", config.getFeatures().getMonitoring().getGrafanaEmailFrom())
			.put("features.monitoring.grafanaEmailTo", config.getFeatures().getMonitoring().getGrafanaEmailTo())
			.put("features.monitoring.grafanaUrl", config.getFeatures().getMonitoring().getGrafanaUrl())
			.put("features.monitoring.namespace", config.getFeatures().getMonitoring().getNamespace())
			.put(
				"features.monitoring.oidc", ToolConfigMapperSupport.oidc(
					config.getFeatures().getMonitoring().getOidc())
			)
			.put("features.monitoring.helm.grafanaImage", helm.getGrafanaImage())
			.put("features.monitoring.helm.grafanaSidecarImage", helm.getGrafanaSidecarImage())
			.put("features.monitoring.helm.prometheusConfigReloaderImage", helm.getPrometheusConfigReloaderImage())
			.put("features.monitoring.helm.prometheusImage", helm.getPrometheusImage())
			.put("features.monitoring.helm.prometheusOperatorImage", helm.getPrometheusOperatorImage())
			.put("jenkins.active", config.getJenkins().getActive())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.put("scm.scmManager.namespace", scmManagerNamespace)
			.put("scm.scmProviderType", config.getScm() == null ? null : config.getScm().getScmProviderType())
			.values();
	}
}
