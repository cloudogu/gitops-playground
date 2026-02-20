package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.kubernetes.api.K8sClient
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(40)
class Registry extends Feature {

    /**
     * Local container port of the registry within the pod
     */
    public static final String CONTAINER_PORT = '5000'

    String namespace
    private Config config
    private K8sClient k8sClient

    Registry(
            Config config,
            FileSystemUtils fileSystemUtils,
            K8sClient k8sClient,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer
    ) {
        this.deployer = deployer
        this.config = config
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient

        if(config.registry.internal) {
            this.namespace = "${config.application.namePrefix}registry"
        }
    }

    @Override
    boolean isEnabled() {
        return config.registry.active
    }

    @Override
    void enable() {

        if (config.registry.internal) {
            addHelmValuesData("service", [
                    nodePort: Config.DEFAULT_REGISTRY_PORT,
                    type    : 'NodePort'
            ])

            def helmConfig = config.registry.helm
            deployHelmChart('registry', 'docker-registry', namespace, helmConfig, "", config)

            if (config.registry.internalPort != Config.DEFAULT_REGISTRY_PORT) {
                /* Add additional node port
               30000 is needed as a static by docker via port mapping of k3d, e.g. 32769 -> 30000 on server-0 container
               See "-p 30000" in init-cluster.sh
               e.g 32769 is needed so the kubelet can access the image inside the server-0 container
             */

               /* k8sClient.createServiceNodePort('docker-registry-internal-port',
                        CONTAINER_PORT, config.registry.internalPort.toString(),
                        namespace) */

                Map<String, String> selector = new HashMap<>()
                selector.put("app", "docker-registry")
                k8sClient.k8sJavaApiClient.createNodePortService(namespace,
                        'docker-registry-internal-port',
                        selector,
                        CONTAINER_PORT.toInteger(),
                        config.registry.internalPort,
                        'docker-registry-internal-port')
            }
        }
    }
}