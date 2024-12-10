package com.cloudogu.gitops

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.K8sClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A feature that relies on container images running inside the kubernetes cluster.
 */
trait FeatureWithImage {

    final Logger log = LoggerFactory.getLogger(this.class)

    void createImagePullSecret() {
        if (config.registry.createImagePullSecrets) {
            String prefixedNamespace = getPrefixedNamespace()
            log.trace("Creating image pull secret 'proxy-registry' in namespace ${prefixedNamespace}")
            String url = config.registry.proxyUrl ?: config.registry.url
            String user = config.registry.proxyUsername ?: config.registry.readOnlyUsername ?: config.registry.username
            String password = config.registry.proxyPassword ?: config.registry.readOnlyPassword ?: config.registry.password

            k8sClient.createNamespace(prefixedNamespace)
            k8sClient.createImagePullSecret('proxy-registry', prefixedNamespace, url, user, password)
        }
    }

    String getPrefixedNamespace() {
        return config.application.namePrefix + namespace
    }

    abstract String getNamespace()

    abstract K8sClient getK8sClient()

    abstract Config getConfig()
}