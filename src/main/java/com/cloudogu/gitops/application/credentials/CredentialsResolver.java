package com.cloudogu.gitops.application.credentials;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class CredentialsResolver {

	private static final String INCOMPLETE_SECRET_REFERENCE =
		"Kubernetes Secret credentials require both secretName and secretNamespace";

	private final K8sClient k8sClient;

	public ResolvedCredentials resolve(
		Credentials reference,
		String fallbackUsername,
		String fallbackPassword) {
		return resolveReference(CredentialsReference.from(reference), fallbackUsername, fallbackPassword);
	}

	public ResolvedCredentials resolveReference(
		CredentialsReference reference,
		String fallbackUsername,
		String fallbackPassword) {
		if (reference == null) {
			return new ResolvedCredentials(fallbackUsername, fallbackPassword);
		}

		boolean secretNameConfigured = hasText(reference.secretName());
		boolean secretNamespaceConfigured = hasText(reference.secretNamespace());

		if (!secretNameConfigured && !secretNamespaceConfigured) {
			return new ResolvedCredentials(fallbackUsername, fallbackPassword);
		}
		if (secretNameConfigured != secretNamespaceConfigured) {
			throw new IllegalArgumentException(INCOMPLETE_SECRET_REFERENCE);
		}

		Credentials referenceWithFallback = new Credentials(
			fallbackUsername,
			null,
			reference.secretName(),
			reference.secretNamespace(),
			reference.usernameKey(),
			reference.passwordKey()
		);

		Credentials resolved = k8sClient.getCredentialsFromSecret(referenceWithFallback);
		return new ResolvedCredentials(resolved.getUsername(), resolved.getPassword());
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
