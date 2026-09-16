package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Singleton
@RequiredArgsConstructor
public class IngressToolConfigMapper implements ToolConfigMapper<IngressToolConfig> {

	private final Config config;

	@Override
	public IngressToolConfig map(DeploymentContext context) {
		Config.IngressSchema ingress = config.getFeatures().getIngress();

		return IngressToolConfig.builder()
								.active(ingress.getActive())
								.namespace(config.getApplication().getNamePrefix() + ingress.getIngressNamespace())
								.helm(ToolConfigMapperSupport.helmChart(
									ingress.getHelm(),
									config.getApplication().getLocalHelmChartFolder()
								))
								.imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
								.templateConfig(templateConfig(config))
								.build();
	}

	private static Map<String, Object> templateConfig(Config config) {
		return new TemplateConfig()
			.put("application.namePrefix", config.getApplication().getNamePrefix())
			.put("application.netpols", config.getApplication().getNetpols())
			.put("features.ingress.helm.image", config.getFeatures().getIngress().getHelm().getImage())
			.put("features.monitoring.active", config.getFeatures().getMonitoring().getActive())
			.put("features.monitoring.namespace", config.getFeatures().getMonitoring().getNamespace())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.values();
	}
}
