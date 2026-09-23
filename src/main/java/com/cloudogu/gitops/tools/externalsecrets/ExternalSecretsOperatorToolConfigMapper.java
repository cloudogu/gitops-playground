package com.cloudogu.gitops.tools.externalsecrets;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@Singleton
@RequiredArgsConstructor
public class ExternalSecretsOperatorToolConfigMapper implements ToolConfigMapper<ExternalSecretsOperatorToolConfig> {

	private final Config config;

	@Override
	public ExternalSecretsOperatorToolConfig map(DeploymentContext context) {
		Config.SecretsSchema secrets = config.getFeatures().getSecrets();
		Config.SecretsSchema.ESOSchema externalSecrets = secrets.getExternalSecrets();
		List<ManagedExternalSecretConfig> managedSecrets = managedSecrets(externalSecrets);
		ExternalVaultConfig externalVault = externalVault(externalSecrets, managedSecrets);
		validateExternalVaultConfig(externalVault, managedSecrets);

		return ExternalSecretsOperatorToolConfig.builder()
			.active(externalSecrets.getActive())
			.namespace(config.getApplication().getNamePrefix() + secrets.getNamespace())
			.operator(context.isArgoCdOperator())
			.skipCrds(config.getApplication().getSkipCrds())
			.netpols(config.getApplication().getNetpols())
			.helm(ToolConfigMapperSupport.helmChart(
				externalSecrets.getHelm(),
				config.getApplication().getLocalHelmChartFolder()
			))
			.imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
			.externalVault(externalVault)
			.managedSecrets(managedSecrets)
			.templateConfig(templateConfig(config))
			.build();
	}

	private static ExternalVaultConfig externalVault(
		Config.SecretsSchema.ESOSchema externalSecrets,
		List<ManagedExternalSecretConfig> managedSecrets) {
		Config.SecretsSchema.ESOSchema.ExternalVaultSchema vault = externalSecrets.getVault();
		Config.SecretsSchema.ESOSchema.VaultAuthSchema auth = vault == null ? null : vault.getAuth();
		Config.SecretsSchema.ESOSchema.TokenSecretRefSchema tokenSecretRef =
			auth == null ? null : auth.getTokenSecretRef();
		List<String> targetNamespaces = managedSecrets.stream()
			.map(ManagedExternalSecretConfig::namespace)
			.distinct()
			.toList();

		return ExternalVaultConfig.builder()
			.storeName(vault == null ? null : vault.getStoreName())
			.server(vault == null ? null : vault.getServer())
			.path(vault == null ? null : vault.getPath())
			.version(vault == null ? null : vault.getVersion())
			.tokenSecretName(tokenSecretRef == null ? null : tokenSecretRef.getName())
			.tokenSecretKey(tokenSecretRef == null ? null : tokenSecretRef.getKey())
			.targetNamespaces(targetNamespaces)
			.build();
	}

	private static List<ManagedExternalSecretConfig> managedSecrets(Config.SecretsSchema.ESOSchema externalSecrets) {
		if (externalSecrets.getSecrets() == null) {
			return List.of();
		}

		return externalSecrets.getSecrets().stream()
			.map(secret -> ManagedExternalSecretConfig.builder()
				.name(secret.getName())
				.namespace(secret.getNamespace())
				.remoteKey(secret.getRemoteKey())
				.data(secret.getData())
				.build())
			.toList();
	}

	private static void validateExternalVaultConfig(
		ExternalVaultConfig vault,
		List<ManagedExternalSecretConfig> managedSecrets) {
		if (managedSecrets.isEmpty()) {
			return;
		}

		requireText(vault.storeName(), "features.secrets.externalSecrets.vault.storeName");
		requireText(vault.server(), "features.secrets.externalSecrets.vault.server");
		requireText(vault.path(), "features.secrets.externalSecrets.vault.path");
		requireText(vault.version(), "features.secrets.externalSecrets.vault.version");
		requireText(vault.tokenSecretName(), "features.secrets.externalSecrets.vault.auth.tokenSecretRef.name");
		requireText(vault.tokenSecretKey(), "features.secrets.externalSecrets.vault.auth.tokenSecretRef.key");

		for (ManagedExternalSecretConfig secret : managedSecrets) {
			requireText(secret.name(), "features.secrets.externalSecrets.secrets[].name");
			requireText(secret.namespace(), "features.secrets.externalSecrets.secrets[].namespace");
			requireText(secret.remoteKey(), "features.secrets.externalSecrets.secrets[].remoteKey");
			if (secret.data() == null || secret.data().isEmpty()) {
				throw new IllegalArgumentException(
					"features.secrets.externalSecrets.secrets[].data must contain at least one key mapping"
				);
			}
			secret.data().forEach((secretKey, property) -> {
				requireText(secretKey, "features.secrets.externalSecrets.secrets[].data key");
				requireText(property, "features.secrets.externalSecrets.secrets[].data property");
			});
		}
	}

	private static void requireText(String value, String configKey) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(configKey + " must be configured when external Vault secrets are used");
		}
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
