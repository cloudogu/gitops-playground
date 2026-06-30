package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(30)
class Registry extends Tool {

	/**
	 * Local container port of the registry within the pod*/
	public static final String CONTAINER_PORT = '5000'

	String namespace
	private K8sClient k8sClient

	Registry(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		// For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
		Deployer deployer) {
		this.deployer = deployer
		this.context = context
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils

		if (config.registry.internal) {
			this.namespace = "${config.application.namePrefix}${config.registry.namespace}"
		}
	}

	@Override
	boolean isEnabled() {
		return config.registry.active
	}

	@Override
	void enable() {

		if (config.registry.internal) {
			addHelmValuesData("service", [nodePort: Config.DEFAULT_REGISTRY_PORT,
			                              type    : 'NodePort'])

			def helmConfig = config.registry.helm
			deployHelmChart('registry', 'docker-registry', namespace, helmConfig, "", context, true)

			if (config.registry.internalPort != Config.DEFAULT_REGISTRY_PORT) {
				/* Add additional node port
			   30000 is needed as a static by docker via port mapping of k3d, e.g. 32769 -> 30000 on server-0 container
			   See "-p 30000" in init-cluster.sh
			   e.g 32769 is needed so the kubelet can access the image inside the server-0 container
			 */

				/* k8sClient.createServiceNodePort('docker-registry-internal-port',
						 CONTAINER_PORT, config.registry.internalPort.toString(),
						 namespace) */

				k8sClient.createServiceNodePort('docker-registry-internal-port',
					"${CONTAINER_PORT}:${CONTAINER_PORT}",
					config.registry.internalPort.toString(),
					namespace)
			}
		}
	}
}