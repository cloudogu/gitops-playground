package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
@Order(60)
class Registry extends Feature {

    /**
     * Local container port of the registry within the pod
     */
    public static final String CONTAINER_PORT = '5000'
    
    private Map config
    private DeploymentStrategy deployer
    private FileSystemUtils fileSystemUtils
    private Path tmpHelmValues
    private K8sClient k8sClient

    Registry(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            K8sClient k8sClient,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        tmpHelmValues = fileSystemUtils.createTempFile()
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {
        
        if (config.registry['internal']) {
            deployRegistry()
        }
        
        if (config.registry['createImagePullSecrets']) {
            String registryUsername = config.registry['readOnlyUsername'] ?: config.registry['username']
            String registryPassword = config.registry['readOnlyPassword'] ?: config.registry['password']
            
            // Name prefix is added by k8sClient
            List exampleAppNamespaces = [ "example-apps-staging", "example-apps-production"]
            exampleAppNamespaces.each {
                def namespace = it as String
                def registrySecretName = 'registry'
                k8sClient.createImagePullSecret(registrySecretName, namespace, 
                        config.registry['url'] as String/* Only domain matters, path would be ignored */,
                        registryUsername, registryPassword)
                
                k8sClient.patch('serviceaccount', 'default', namespace,
                        [ imagePullSecrets: [ [name: registrySecretName] ]])

                if (config.registry['twoRegistries']) {
                    k8sClient.createImagePullSecret('proxy-registry', namespace,
                            config.registry['proxyUrl'] as String, config.registry['proxyUsername'] as String,
                            config.registry['proxyPassword'] as String)
                }
            }
        }

    }

    private void deployRegistry() {
        def helmConfig = config['registry']['helm']

        Map yaml = [
                service: [
                        nodePort: ApplicationConfigurator.DEFAULT_REGISTRY_PORT,
                        type    : 'NodePort'
                ]
        ]
        log.trace("Helm yaml to be applied: ${yaml}")
        fileSystemUtils.writeYaml(yaml, tmpHelmValues.toFile())

        if (config['registry']['internalPort'] != ApplicationConfigurator.DEFAULT_REGISTRY_PORT) {
            /* Add additional node port
               30000 is needed as a static by docker via port mapping of k3d, e.g. 32769 -> 30000 on server-0 container
               See "-p 30000" in init-cluster.sh
               e.g 32769 is needed so the kubelet can access the image inside the server-0 container
             */
            k8sClient.createServiceNodePort('docker-registry-internal-port',
                    CONTAINER_PORT, config['registry']['internalPort'] as String,
                    'default')
        }

        deployer.deployFeature(
                helmConfig['repoURL'] as String,
                'registry',
                helmConfig['chart'] as String,
                helmConfig['version'] as String,
                'default',
                'docker-registry',
                tmpHelmValues
        )
    }
}
