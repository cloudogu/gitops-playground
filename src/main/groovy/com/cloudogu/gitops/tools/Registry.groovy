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
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
@Order(30)
class Registry extends Tool {

	/**
	 * Local container port of the registry within the pod	*/
	public static final String CONTAINER_PORT = '5000'

	private static final String TOOL_NAME = 'registry'
	private static final String RELEASE_NAME = 'docker-registry'

	String namespace
	private K8sClient k8sClient

	Registry(FileSystemUtils fileSystemUtils,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		// Bootstrap with Helm first, then create an ArgoCD Application for GitOps management.
		Deployer deployer) {
		this.deployer = deployer
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.registry.active
	}

	@Override
	protected void preDeploy() {
		if (!isInternalRegistry()) {
			return
		}
		this.namespace = resolveNamespace(context)
		prepareRegistryHelmValues()
	}

	@Override
	protected void deploy() {
		if (!isInternalRegistry()) {
			return
		}
		deployInternalRegistry()
		createInternalRegistryNodePortIfRequired()
	}

	@Override
	protected void publishChanges() {
		if (!isInternalRegistry()) {
			return
		}

		publishClusterResourcesChanges(TOOL_NAME)
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		if (!context.config.registry.internal) {
			return null
		}

		return "${context.config.application.namePrefix}${context.config.registry.namespace}"
	}

	private boolean isInternalRegistry() {
		return config.registry.internal
	}

	private void prepareRegistryHelmValues() {
		addHelmValuesData('service',
			[nodePort: Config.DEFAULT_REGISTRY_PORT,
			 type    : 'NodePort'])
	}

	private void deployInternalRegistry() {
		deployHelmChart(TOOL_NAME,
			RELEASE_NAME,
			namespace,
			config.registry.helm,
			'',
			context,
			true)
	}

	private void createInternalRegistryNodePortIfRequired() {
		if (config.registry.internalPort == Config.DEFAULT_REGISTRY_PORT) {
			return
		}

		/*
		 * Add additional node port.
		 *
		 * 30000 is needed as a static port by Docker via k3d port mapping,
		 * e.g. 32769 -> 30000 on the server-0 container.
		 *
		 * See "-p 30000" in init-cluster.sh.
		 * e.g. 32769 is needed so the kubelet can access the image inside the server-0 container.
		 */
		k8sClient.createServiceNodePort('docker-registry-internal-port',
			"${CONTAINER_PORT}:${CONTAINER_PORT}",
			config.registry.internalPort.toString(),
			namespace)
	}
}