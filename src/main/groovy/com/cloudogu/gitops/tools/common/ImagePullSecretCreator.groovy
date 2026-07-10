package com.cloudogu.gitops.tools.common

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

/**
 * Creates the registry image pull secret for tools that deploy workloads into Kubernetes.
 *
 * <p>The creator is intentionally not part of the Tool base class. Tools call it explicitly
 * in their setup flow when an image pull secret is relevant for their namespace.</p>*/
@Slf4j
@Singleton
class ImagePullSecretCreator {

	private static final String IMAGE_PULL_SECRET_NAME = 'proxy-registry'

	private final K8sClient k8sClient

	ImagePullSecretCreator(K8sClient k8sClient) {
		this.k8sClient = k8sClient
	}

	void createIfRequired(Config config, String namespace) {
		if (!config.registry.createImagePullSecrets) {
			return
		}

		if (!namespace) {
			throw new IllegalArgumentException('Namespace must be set before creating an image pull secret.')
		}

		log.trace("Creating image pull secret '${IMAGE_PULL_SECRET_NAME}' in namespace ${namespace}")

		String url = config.registry.proxyUrl ?: config.registry.url
		String user = config.registry.proxyUsername ?: config.registry.readOnlyUsername ?: config.registry.username
		String password = config.registry.proxyPassword ?: config.registry.readOnlyPassword ?: config.registry.password

		k8sClient.createNamespace(namespace)
		k8sClient.createImagePullSecret(IMAGE_PULL_SECRET_NAME, namespace, url, user, password)
	}
}