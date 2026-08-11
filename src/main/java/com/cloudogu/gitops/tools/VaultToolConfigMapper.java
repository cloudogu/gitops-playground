package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class VaultToolConfigMapper implements ToolConfigMapper<VaultToolConfig> {

	@Override
	public VaultToolConfig map(DeploymentContext context) {
		Config config = context.getConfig();
		Config.SecretsSchema secrets = config.getFeatures().getSecrets();
		return VaultToolConfig.builder()
			.active(secrets.getActive())
			.namespace(config.getApplication().getNamePrefix() + secrets.getNamespace())
			.namePrefix(config.getApplication().getNamePrefix())
			.url(secrets.getVault().getUrl())
			.mode(secrets.getVault().getMode())
			.helm(ToolConfigMapperSupport.helmChart(
				secrets.getVault().getHelm(), config.getApplication().getLocalHelmChartFolder()
			))
			.imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
			.templateConfig(templateConfig(config))
			.build();
	}

	private static Map<String, Object> templateConfig(Config config) {
		return new TemplateConfig()
			.put("application.namePrefix", config.getApplication().getNamePrefix())
			.put("application.namespaceIsolation", config.getApplication().getNamespaceIsolation())
			.put("application.openshift", config.getApplication().getOpenshift())
			.put("application.password", config.getApplication().getPassword())
			.put("application.podResources", config.getApplication().getPodResources())
			.put("application.username", config.getApplication().getUsername())
			.put("features.argocd.active", config.getFeatures().getArgocd().getActive())
			.put("features.certManager.active", config.getFeatures().getCertManager().getActive())
			.put("features.certManager.issuer", config.getFeatures().getCertManager().getIssuer())
			.put("features.secrets.vault.oidc", config.getFeatures().getSecrets().getVault().getOidc())
			.put("features.secrets.vault.helm.image", config.getFeatures().getSecrets().getVault().getHelm().getImage())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.values();
	}
}
