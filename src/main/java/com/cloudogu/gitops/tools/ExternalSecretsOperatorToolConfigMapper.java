package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class ExternalSecretsOperatorToolConfigMapper implements ToolConfigMapper<ExternalSecretsOperatorToolConfig> {

	@Override
	public ExternalSecretsOperatorToolConfig map(DeploymentContext context) {
		Config config = context.getConfig();
		Config.SecretsSchema secrets = config.getFeatures().getSecrets();
		return ExternalSecretsOperatorToolConfig.builder()
			.active(secrets.getActive())
			.namespace(config.getApplication().getNamePrefix() + secrets.getNamespace())
			.helm(ToolConfigMapperSupport.helmChart(
				secrets.getExternalSecrets().getHelm(), config.getApplication().getLocalHelmChartFolder()
			))
			.imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
			.templateConfig(templateConfig(config))
			.build();
	}

	private static Map<String, Object> templateConfig(Config config) {
		Config.SecretsSchema.ESOSchema.ESOHelmSchema helm = config.getFeatures()
			.getSecrets()
			.getExternalSecrets()
			.getHelm();
		return new TemplateConfig()
			.put("application.podResources", config.getApplication().getPodResources())
			.put("application.skipCrds", config.getApplication().getSkipCrds())
			.put("features.secrets.externalSecrets.helm.image", helm.getImage())
			.put("features.secrets.externalSecrets.helm.certControllerImage", helm.getCertControllerImage())
			.put("features.secrets.externalSecrets.helm.webhookImage", helm.getWebhookImage())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.values();
	}
}
