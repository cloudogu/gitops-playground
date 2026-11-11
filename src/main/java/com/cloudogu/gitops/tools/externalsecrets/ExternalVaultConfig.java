package com.cloudogu.gitops.tools.externalsecrets;

import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.List;

@Builder
public record ExternalVaultConfig(
	String storeName,
	String server,
	String namespace,
	String path,
	String version,
	CaProvider caProvider,
	VaultAuth auth,
	List<String> targetNamespaces
) {
	public ExternalVaultConfig {
		targetNamespaces = ImmutableConfigData.copyList(targetNamespaces);
	}

	public record CaProvider(String type, String name, String key) {
	}

	public sealed interface VaultAuth permits TokenSecretRef, AppRole, KubernetesAuth {
	}

	public record TokenSecretRef(String name, String key) implements VaultAuth {
	}

	public record AppRole(String path, String roleId, SecretRef secretRef) implements VaultAuth {
	}

	public record SecretRef(String name, String key) {
	}

	public record KubernetesAuth(String mountPath, String role, ServiceAccountRef serviceAccountRef)
		implements VaultAuth {
	}

	public record ServiceAccountRef(String name, List<String> audiences) {
		public ServiceAccountRef {
			audiences = ImmutableConfigData.copyList(audiences);
		}
	}
}
