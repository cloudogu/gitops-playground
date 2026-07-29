package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;
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

	public void createIfRequired(Config config, String namespace) {
		if (!config.getRegistry().getCreateImagePullSecrets()) {
			return;
		}

		if (namespace == null || namespace.isEmpty()) {
			throw new IllegalArgumentException("Namespace must be set before creating an image pull secret.");
		}

		log.trace("Creating image pull secret '{}' in namespace {}", IMAGE_PULL_SECRET_NAME, namespace);

		String url = firstNonBlank(config.getRegistry().getProxyUrl(), config.getRegistry().getUrl());
		String user = firstNonBlank(config.getRegistry().getProxyUsername(), firstNonBlank(config.getRegistry()
		                                                                                         .getReadOnlyUsername(), config.getRegistry()
		                                                                                                                       .getUsername()));
		String password = firstNonBlank(config.getRegistry().getProxyPassword(), firstNonBlank(config.getRegistry()
		                                                                                             .getReadOnlyPassword(), config.getRegistry()
		                                                                                                                           .getPassword()));

		k8sClient.createNamespace(namespace);
		k8sClient.createImagePullSecret(IMAGE_PULL_SECRET_NAME, namespace, url, user, password);
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return (preferred != null && !preferred.isEmpty()) ? preferred : fallback;
	}
}
