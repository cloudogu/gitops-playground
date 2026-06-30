package com.cloudogu.gitops.tools.common

import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A feature that relies on container images running inside the kubernetes cluster.*/
trait ToolWithImage {

	final Logger log = LoggerFactory.getLogger(this.class)

	void createImagePullSecret() {
		def config = context.config
		if (config.registry.createImagePullSecrets) {

			log.trace("Creating image pull secret 'proxy-registry' in namespace ${this.namespace}")
			String url = config.registry.proxyUrl ?: config.registry.url
			String user = config.registry.proxyUsername ?: config.registry.readOnlyUsername ?: config.registry.username
			String password = config.registry.proxyPassword ?: config.registry.readOnlyPassword ?: config.registry.password

			k8sClient.createNamespace(this.namespace)
			k8sClient.createImagePullSecret('proxy-registry', namespace, url, user, password)
		}
	}

	abstract String getNamespace()

	abstract K8sClient getK8sClient()
}