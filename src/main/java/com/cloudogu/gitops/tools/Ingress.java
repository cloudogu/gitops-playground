package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

@Singleton
@Order(150)
@Slf4j
public class Ingress extends AbstractMappedTool<IngressToolConfig> {

	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/traefik/templates/values.ftl.yaml";

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TOOL_NAME = "traefik";
	private static final String RELEASE_NAME = "traefik";
	private static final String INGRESS_APP_PATH = "apps/traefik";
	private static final String NETWORK_POLICY_TEMPLATE =
		"argocd/cluster-resources/apps/traefik/templates/netpols/allow-required-access-to-traefik.ftl.yaml";
	private static final String NETWORK_POLICY_PATH =
		"apps/traefik/netpols/allow-required-access-to-traefik.yaml";

	private final ImagePullSecretCreator imagePullSecretCreator;

	@Getter
	@Setter
	private String namespace;

	public Ingress(
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator,
		IngressToolConfigMapper configMapper) {
		super(configMapper);
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.airGappedUtils = airGappedUtils;
		this.gitHandler = gitHandler;
		this.imagePullSecretCreator = imagePullSecretCreator;
	}

	@Override
	protected boolean isEnabled(IngressToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		this.namespace = activeNamespace(toolConfig());

		createImagePullSecret();
		prepareIngressApp(repositoryWorkspace.getClusterResourcesRepository());
		prepareIngressNetworkPolicy(repositoryWorkspace.getClusterResourcesRepository());
	}

	@Override
	protected void deploy() {
		addHelmValuesData("config", toolConfig().templateConfig());
		deployHelmChart(TOOL_NAME, RELEASE_NAME, namespace, toolConfig().helm(), HELM_VALUES_PATH, context);
	}

	@Override
	protected void publishChanges() {
		publishClusterResourcesChanges(TOOL_NAME);
	}

	@Override
	protected String activeNamespace(IngressToolConfig config) {
		return config.namespace();
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(toolConfig().imagePullSecret(), namespace);
	}

	private void prepareIngressNetworkPolicy(GitRepo clusterResourcesRepo) {
		Path networkPolicyPath = Path.of(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), NETWORK_POLICY_PATH);
		if (!toolConfig().netpols()) {
			FileSystemUtils.deleteFile(networkPolicyPath.toString());
			return;
		}

		try {
			String networkPolicyYaml = new TemplatingEngine().template(
				new File(NETWORK_POLICY_TEMPLATE),
				Map.of(
					"namespace", namespace,
					"monitoringActive", toolConfig().monitoringActive(),
					"monitoringNamespace", toolConfig().monitoringNamespace()
				)
			);
			clusterResourcesRepo.writeFile(NETWORK_POLICY_PATH, networkPolicyYaml);
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate Traefik NetworkPolicy", e);
		}
	}

	private static void prepareIngressApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing ingress repository content in {}", clusterResourcesRepo.getRepoTarget());

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, INGRESS_APP_PATH)
		);
	}
}
