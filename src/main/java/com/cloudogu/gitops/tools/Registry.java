package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
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
	private static final String NETWORK_POLICY_TEMPLATE =
		"argocd/cluster-resources/apps/registry/templates/netpols/allow-required-access-to-registry.ftl.yaml";
	private static final String NETWORK_POLICY_PATH =
		"apps/registry/netpols/allow-required-access-to-registry.yaml";

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
		prepareRegistryNetworkPolicy(repositoryWorkspace.getClusterResourcesRepository());
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

	private void prepareRegistryNetworkPolicy(GitRepo clusterResourcesRepo) {
		Path networkPolicyPath = Path.of(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), NETWORK_POLICY_PATH);
		if (!toolConfig().netpols()) {
			FileSystemUtils.deleteFile(networkPolicyPath.toString());
			return;
		}

		if (toolConfig().registryAccessCidrs().isEmpty()) {
			log.warn(
				"No registry access CIDRs configured. External access to the internal registry will remain blocked."
			);
		}

		try {
			String networkPolicyYaml = new TemplatingEngine().template(
				new File(NETWORK_POLICY_TEMPLATE),
				Map.of(
					"namespace", namespace,
					"registryAccessCidrs", toolConfig().registryAccessCidrs()
				)
			);
			clusterResourcesRepo.writeFile(NETWORK_POLICY_PATH, networkPolicyYaml);
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate registry NetworkPolicy", e);
		}
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
