package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
@Order(30)
class Registry extends Tool {

	/**
	 * Local container port of the registry within the pod.	*/
	static final String CONTAINER_PORT = '5000'

	private static final String TOOL_NAME = 'registry'
	private static final String RELEASE_NAME = 'docker-registry'

	private final HelmToolDeployer helmToolDeployer
	private final K8sClient k8sClient

	String namespace

	Registry(HelmToolDeployer helmToolDeployer,
		K8sClient k8sClient) {
		this.helmToolDeployer = helmToolDeployer
		this.k8sClient = k8sClient
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.registry.active && context.config.registry.internal
	}

	@Override
	protected void preDeploy() {
		this.namespace = resolveNamespace(context)
	}

	@Override
	protected void deploy() {
		deployInternalRegistry()
		createInternalRegistryNodePortIfRequired()
	}

	@Override
	protected void publishChanges() {
		publishClusterResourcesChanges(TOOL_NAME)
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}" + "${context.config.registry.namespace}"
	}

	private void deployInternalRegistry() {
		Map<String, Object> templateData = [service: [nodePort: Config.DEFAULT_REGISTRY_PORT,
		                                              type    : 'NodePort']] as Map<String, Object>

		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				RELEASE_NAME,
				namespace,
				config.registry.helm,
				'',
				templateData,
				true)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)
	}

	private void createInternalRegistryNodePortIfRequired() {
		if (config.registry.internalPort == Config.DEFAULT_REGISTRY_PORT) {
			return
		}

		/*
		 * Add an additional node port.
		 *
		 * 30000 is needed as a static port by Docker via k3d port
		 * mapping, for example 32769 -> 30000 on the server-0
		 * container.
		 *
		 * See "-p 30000" in init-cluster.sh.
		 * The external port is needed so that the kubelet can access
		 * the registry inside the server-0 container.
		 */
		k8sClient.createServiceNodePort('docker-registry-internal-port',
			"${CONTAINER_PORT}:${CONTAINER_PORT}",
			config.registry.internalPort.toString(),
			namespace)
	}
}