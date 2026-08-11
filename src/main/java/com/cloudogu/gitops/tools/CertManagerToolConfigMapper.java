package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class CertManagerToolConfigMapper implements ToolConfigMapper<CertManagerToolConfig> {

	@Override
	public CertManagerToolConfig map(DeploymentContext context) {
		Config config = context.getConfig();
		Config.CertManagerSchema certManager = config.getFeatures().getCertManager();
		return CertManagerToolConfig.builder()
			.active(certManager.getActive())
			.namespace(config.getApplication().getNamePrefix() + certManager.getNamespace())
			.helm(ToolConfigMapperSupport.helmChart(certManager.getHelm(), config.getApplication().getLocalHelmChartFolder()))
			.imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
			.templateConfig(templateConfig(config))
			.build();
	}

	private static Map<String, Object> templateConfig(Config config) {
		Config.CertManagerSchema certManager = config.getFeatures().getCertManager();
		Config.CertManagerSchema.CertManagerHelmSchema helm = certManager.getHelm();
		return new TemplateConfig()
			.put("application.podResources", config.getApplication().getPodResources())
			.put("application.skipCrds", config.getApplication().getSkipCrds())
			.put("features.certManager.issuer", certManager.getIssuer())
			.put("features.certManager.helm.image", helm.getImage())
			.put("features.certManager.helm.webhookImage", helm.getWebhookImage())
			.put("features.certManager.helm.cainjectorImage", helm.getCainjectorImage())
			.put("features.certManager.helm.acmeSolverImage", helm.getAcmeSolverImage())
			.put("features.certManager.helm.startupAPICheckImage", helm.getStartupAPICheckImage())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.values();
	}
}
