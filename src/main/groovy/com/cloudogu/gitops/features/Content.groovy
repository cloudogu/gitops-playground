package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.utils.K8sClient
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(80)
class Content extends Feature {

    Config config
    K8sClient k8sClient
    
    Content(
            Config config,
            K8sClient k8sClient
    ) {
        this.config = config
        this.k8sClient = k8sClient
    }


    @Override
    boolean isEnabled() {
        return true // for now always on. Once we refactor from Argo CD class we add a param to enable
    }

    @Override
    void enable() {
        if (config.registry.createImagePullSecrets) {
            String registryUsername = config.registry.readOnlyUsername ?: config.registry.username
            String registryPassword = config.registry.readOnlyPassword ?: config.registry.password

            List exampleAppNamespaces = [ "example-apps-staging", "example-apps-production"]
            exampleAppNamespaces.each {
                String namespace = "${config.application.namePrefix}${it}"
                def registrySecretName = 'registry'

                k8sClient.createNamespace(namespace)
                        
                k8sClient.createImagePullSecret(registrySecretName, namespace,
                        config.registry.url /* Only domain matters, path would be ignored */,
                        registryUsername, registryPassword)

                k8sClient.patch('serviceaccount', 'default', namespace,
                        [ imagePullSecrets: [ [name: registrySecretName] ]])

                if (config.registry.twoRegistries) {
                    k8sClient.createImagePullSecret('proxy-registry', namespace,
                            config.registry.proxyUrl, config.registry.proxyUsername,
                            config.registry.proxyPassword)
                }
            }
        }
    }
}