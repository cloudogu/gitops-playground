package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.credentials.CredentialsReference;
import com.cloudogu.gitops.application.credentials.CredentialsResolver;
import com.cloudogu.gitops.application.credentials.ResolvedCredentials;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the registry image pull secret for tools that deploy workloads into Kubernetes.
 *
 * <p>The creator is intentionally not part of the AbstractTool base class. Tools call it explicitly
 * in their setup flow when an image pull secret is relevant for their namespace.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class ImagePullSecretCreator {

	private static final String IMAGE_PULL_SECRET_NAME = "proxy-registry";

	private final K8sClient k8sClient;
	private final CredentialsResolver credentialsResolver;

	public void createIfRequired(ImagePullSecretConfig config, String namespace) {
		if (!config.create()) {
			return;
		}

		if (namespace == null || namespace.isEmpty()) {
			throw new IllegalArgumentException("Namespace must be set before creating an image pull secret.");
		}

		log.trace("Creating image pull secret '{}' in namespace {}", IMAGE_PULL_SECRET_NAME, namespace);

		String url = firstNonBlank(config.proxyUrl(), config.url());
		ResolvedCredentials credentials = resolveCredentials(config);

		k8sClient.createNamespace(namespace);
		k8sClient.createImagePullSecret(
			IMAGE_PULL_SECRET_NAME,
			namespace,
			url,
			credentials.username(),
			credentials.password()
		);
	}

	private ResolvedCredentials resolveCredentials(ImagePullSecretConfig config) {
		if (hasConfiguredReference(config.proxyCredentials())
			|| hasCompletePlainCredentials(config.proxyUsername(), config.proxyPassword())) {
			return credentialsResolver.resolveReference(
				config.proxyCredentials(), config.proxyUsername(), config.proxyPassword()
			);
		}
		if (hasConfiguredReference(config.readOnlyCredentials())
			|| hasCompletePlainCredentials(config.readOnlyUsername(), config.readOnlyPassword())) {
			return credentialsResolver.resolveReference(
				config.readOnlyCredentials(), config.readOnlyUsername(), config.readOnlyPassword()
			);
		}
		if (hasConfiguredReference(config.credentials())
			|| hasCompletePlainCredentials(config.username(), config.password())) {
			return credentialsResolver.resolveReference(config.credentials(), config.username(), config.password());
		}

		return new ResolvedCredentials(
			firstNonBlank(config.proxyUsername(), firstNonBlank(config.readOnlyUsername(), config.username())),
			firstNonBlank(config.proxyPassword(), firstNonBlank(config.readOnlyPassword(), config.password()))
		);
	}

	private static boolean hasCompletePlainCredentials(String username, String password) {
		return hasText(username) && hasText(password);
	}

	private static boolean hasConfiguredReference(CredentialsReference reference) {
		return reference != null
			&& (hasText(reference.secretName()) || hasText(reference.secretNamespace()));
	}

	private static boolean hasText(String value) {
		return value != null && !value.isEmpty();
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return hasText(preferred) ? preferred : fallback;
	}
}
