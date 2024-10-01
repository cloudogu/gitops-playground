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
        if (config.registry['createImagePullSecrets']) {
            log.trace("Creating image pull secret 'proxy-registry' in namespace ${namespace}" as String)
            String url = config.registry['proxyUrl'] ?: config.registry['url']
            String user = config.registry['proxyUsername'] ?: config.registry['readOnlyUsername'] ?: config.registry['username']
            String password = config.registry['proxyPassword'] ?: config.registry['readOnlyPassword'] ?: config.registry['password']
            
            k8sClient.createNamespace(namespace)
            k8sClient.createImagePullSecret('proxy-registry', namespace, url, user, password)
        }
    }
    
    abstract String getNamespace()
    abstract K8sClient getK8sClient()
    abstract Map getConfig()
}