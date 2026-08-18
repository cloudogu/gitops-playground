package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Singleton
@Order(30)
@Slf4j
public class Registry extends AbstractMappedTool<RegistryToolConfig> {

	/**
	 * Local container port of the registry within the pod
	 */
	public static final String CONTAINER_PORT = "5000";

	private static final String TOOL_NAME = "registry";
	private static final String RELEASE_NAME = "docker-registry";

	private final K8sClient k8sClient;

	@Getter
	@Setter
	private String namespace;

	public Registry(
		FileSystemUtils fileSystemUtils, K8sClient k8sClient, AirGappedUtils airGappedUtils,
		// Bootstrap with Helm first, then create an ArgoCD Application for GitOps management.
		Deployer deployer,
		RegistryToolConfigMapper configMapper) {
		super(configMapper);
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.k8sClient = k8sClient;
		this.airGappedUtils = airGappedUtils;
	}

	@Override
	protected boolean isEnabled(RegistryToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		if (!isInternalRegistry()) {
			return;
		}

		this.namespace = activeNamespace(toolConfig());

		prepareRegistryHelmValues();
	}

	@Override
	protected void deploy() {
		if (!isInternalRegistry()) {
			return;
		}

		deployInternalRegistry();
		createInternalRegistryNodePortIfRequired();
	}

	@Override
	protected void publishChanges() {
		if (!isInternalRegistry()) {
			return;
		}

		publishClusterResourcesChanges(TOOL_NAME);
	}

	@Override
	protected String activeNamespace(RegistryToolConfig config) {
		return config.namespace();
	}

	private boolean isInternalRegistry() {
		return toolConfig().internal();
	}

	private void prepareRegistryHelmValues() {
		Map<String, Object> service = new HashMap<>();
		service.put("nodePort", toolConfig().bootstrapNodePort());
		service.put("type", "NodePort");
		addHelmValuesData("service", service);
	}

	private void deployInternalRegistry() {
		deployHelmChart(
			TOOL_NAME, RELEASE_NAME, namespace, toolConfig().helm(), "", context, true
		);
	}

	private void createInternalRegistryNodePortIfRequired() {
		if (toolConfig().internalPort() == toolConfig().bootstrapNodePort()) {
			return;
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
		k8sClient.createServiceNodePort(
			"docker-registry-internal-port", CONTAINER_PORT + ":" + CONTAINER_PORT,
			toolConfig().internalPort().toString(), namespace
		);
	}
}
