package com.cloudogu.gitops

import com.cloudogu.gitops.utils.K8sClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A feature that relies on container images running inside the kubernetes cluster.
 */
trait FeatureWithImage {

    final Logger log = LoggerFactory.getLogger(this.class)
    
    void createImagePullSecret() {
        if (config.registry['createImagePullSecrets'] && config.registry['twoRegistries']) {
            log.trace("Creating image pull secret 'proxy-registry' in namespace ${namespace}" as String)
            k8sClient.createNamespace(namespace)
            k8sClient.createImagePullSecret('proxy-registry', namespace, config.registry['proxyUrl'] as String,
                    config.registry['proxyUsername'] as String,
                    config.registry['proxyPassword'] as String)
        }
    }
    
    abstract String getNamespace()
    abstract K8sClient getK8sClient()
    abstract Map getConfig()
}