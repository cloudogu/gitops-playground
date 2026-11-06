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
		validateVaultAuth(externalSecrets.getVault(), managedSecrets);

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
												.templateConfig(templateConfig(config, context))
												.build();
	}

	private static ExternalVaultConfig externalVault(
		Config.SecretsSchema.ESOSchema externalSecrets,
		List<ManagedExternalSecretConfig> managedSecrets) {
		Config.SecretsSchema.ESOSchema.ExternalVaultSchema vault = externalSecrets.getVault();
		Config.SecretsSchema.ESOSchema.CaProviderSchema caProvider = vault == null ? null : vault.getCaProvider();
		Config.SecretsSchema.ESOSchema.VaultAuthSchema auth = vault == null ? null : vault.getAuth();
		List<String> targetNamespaces = managedSecrets.stream()
													  .map(ManagedExternalSecretConfig::namespace)
													  .distinct()
													  .toList();

		return ExternalVaultConfig.builder()
								  .storeName(vault == null ? null : vault.getStoreName())
								  .server(vault == null ? null : vault.getServer())
								  .path(vault == null ? null : vault.getPath())
								  .version(vault == null ? null : vault.getVersion())
								  .caProvider(caProvider == null ? null : new ExternalVaultConfig.CaProvider(
									  caProvider.getType(), caProvider.getName(), caProvider.getKey()
								  ))
								  .auth(vaultAuth(auth))
								  .targetNamespaces(targetNamespaces)
								  .build();
	}

	private static ExternalVaultConfig.VaultAuth vaultAuth(Config.SecretsSchema.ESOSchema.VaultAuthSchema auth) {
		if (auth == null) {
			return null;
		}

		if (auth.getTokenSecretRef() != null) {
			Config.SecretsSchema.ESOSchema.TokenSecretRefSchema token = auth.getTokenSecretRef();
			return new ExternalVaultConfig.TokenSecretRef(token.getName(), token.getKey());
		}
		if (auth.getAppRole() != null) {
			Config.SecretsSchema.ESOSchema.AppRoleSchema appRole = auth.getAppRole();
			Config.SecretsSchema.ESOSchema.AppRoleSecretRefSchema secret = appRole.getSecretRef();
			return new ExternalVaultConfig.AppRole(
				appRole.getPath(),
				appRole.getRoleId(),
				secret == null ? null : new ExternalVaultConfig.SecretRef(secret.getName(), secret.getKey())
			);
		}
		if (auth.getKubernetes() != null) {
			Config.SecretsSchema.ESOSchema.KubernetesAuthSchema kubernetes = auth.getKubernetes();
			Config.SecretsSchema.ESOSchema.KubernetesServiceAccountRefSchema account = kubernetes.getServiceAccountRef();
			return new ExternalVaultConfig.KubernetesAuth(
				kubernetes.getMountPath(),
				kubernetes.getRole(),
				account == null ? null : new ExternalVaultConfig.ServiceAccountRef(account.getName(), account.getAudiences())
			);
		}
		return null;
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
																		.refreshInterval(secret.getRefreshInterval())
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
		if (vault.caProvider() != null) {
			ExternalVaultConfig.CaProvider caProvider = vault.caProvider();
			String keyPrefix = "features.secrets.externalSecrets.vault.caProvider.";
			requireText(caProvider.type(), keyPrefix + "type");
			if (!"Secret".equals(caProvider.type()) && !"ConfigMap".equals(caProvider.type())) {
				throw new IllegalArgumentException(keyPrefix + "type must be Secret or ConfigMap");
			}
			requireText(caProvider.name(), keyPrefix + "name");
			requireText(caProvider.key(), keyPrefix + "key");
		}

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

	private static void validateVaultAuth(
		Config.SecretsSchema.ESOSchema.ExternalVaultSchema vault,
		List<ManagedExternalSecretConfig> managedSecrets) {
		if (managedSecrets.isEmpty()) {
			return;
		}

		String authKey = "features.secrets.externalSecrets.vault.auth";
		Config.SecretsSchema.ESOSchema.VaultAuthSchema auth =
			vault == null ? null : vault.getAuth();

		validateExactlyOneAuthMethod(auth, authKey);

		if (auth.getTokenSecretRef() != null) {
			Config.SecretsSchema.ESOSchema.TokenSecretRefSchema token = auth.getTokenSecretRef();
			requireText(token.getName(), authKey + ".tokenSecretRef.name");
			requireText(token.getKey(), authKey + ".tokenSecretRef.key");
		} else if (auth.getAppRole() != null) {
			Config.SecretsSchema.ESOSchema.AppRoleSchema appRole = auth.getAppRole();
			requireText(appRole.getPath(), authKey + ".appRole.path");
			requireText(appRole.getRoleId(), authKey + ".appRole.roleId");
			Config.SecretsSchema.ESOSchema.AppRoleSecretRefSchema secretRef = appRole.getSecretRef();
			requireText(secretRef == null ? null : secretRef.getName(), authKey + ".appRole.secretRef.name");
			requireText(secretRef == null ? null : secretRef.getKey(), authKey + ".appRole.secretRef.key");
		} else {
			Config.SecretsSchema.ESOSchema.KubernetesAuthSchema kubernetes = auth.getKubernetes();
			requireText(kubernetes.getMountPath(), authKey + ".kubernetes.mountPath");
			requireText(kubernetes.getRole(), authKey + ".kubernetes.role");
			Config.SecretsSchema.ESOSchema.KubernetesServiceAccountRefSchema account =
				kubernetes.getServiceAccountRef();
			requireText(account == null ? null : account.getName(), authKey + ".kubernetes.serviceAccountRef.name");
			if (account != null && account.getAudiences() != null) {
				for (String audience : account.getAudiences()) {
					requireText(audience, authKey + ".kubernetes.serviceAccountRef.audiences[]");
				}
			}
		}
	}

	private static void validateExactlyOneAuthMethod(
		Config.SecretsSchema.ESOSchema.VaultAuthSchema auth,
		String authKey) {

		int configuredMethods = 0;

		if (auth != null) {
			if (auth.getTokenSecretRef() != null) {
				configuredMethods++;
			}
			if (auth.getAppRole() != null) {
				configuredMethods++;
			}
			if (auth.getKubernetes() != null) {
				configuredMethods++;
			}
		}

		if (configuredMethods != 1) {
			throw new IllegalArgumentException(authKey + " must configure exactly one of tokenSecretRef, appRole, or kubernetes");
		}
	}

	private static void requireText(String value, String configKey) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(configKey + " must be configured when external Vault secrets are used");
		}
	}

	private static Map<String, Object> templateConfig(Config config, DeploymentContext context) {
		Config.SecretsSchema.ESOSchema.ESOHelmSchema helm = config.getFeatures()
																  .getSecrets()
																  .getExternalSecrets()
																  .getHelm();
		return new TemplateConfig()
			.put("application.podResources", config.getApplication().getPodResources())
			.put("application.skipCrds", config.getApplication().getSkipCrds())
			.put("features.argocd.operator", context.isArgoCdOperator())
			.put("features.secrets.externalSecrets.helm.image", helm.getImage())
			.put("features.secrets.externalSecrets.helm.certControllerImage", helm.getCertControllerImage())
			.put("features.secrets.externalSecrets.helm.webhookImage", helm.getWebhookImage())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.values();
	}
}
