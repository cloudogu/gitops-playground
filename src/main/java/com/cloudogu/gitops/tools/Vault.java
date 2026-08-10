package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
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
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Singleton
@Order(500)
@Slf4j
public class Vault extends AbstractMappedTool<VaultToolConfig> {

	public static final String VAULT_START_SCRIPT_PATH = "argocd/cluster-resources/apps/vault/templates/dev-post-start.ftl.sh";
	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/vault/templates/values.ftl.yaml";

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TOOL_NAME = "vault";
	private static final String RELEASE_NAME = "vault";
	private static final String VAULT_APP_PATH = "apps/vault";

	private final ImagePullSecretCreator imagePullSecretCreator;
	private final K8sClient k8sClient;

	@Getter
	@Setter
	private String namespace;

	public Vault(
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator,
		VaultToolConfigMapper configMapper) {
		super(configMapper);
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.k8sClient = k8sClient;
		this.airGappedUtils = airGappedUtils;
		this.gitHandler = gitHandler;
		this.imagePullSecretCreator = imagePullSecretCreator;
	}

	@Override
	protected boolean isEnabled(VaultToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		this.namespace = activeNamespace(toolConfig());

		createImagePullSecret();
		prepareVaultApp(repositoryWorkspace.getClusterResourcesRepository());
		replaceVaultTemplates(repositoryWorkspace.getClusterResourcesRepository());
		prepareVaultHelmValues();
		prepareDevModeIfRequired();
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
	protected String activeNamespace(VaultToolConfig config) {
		return config.namespace();
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(toolConfig().imagePullSecret(), namespace);
	}

	private void prepareVaultHelmValues() {
		String url = toolConfig().url();
		try {
			addHelmValuesData("host", (url != null && !url.isEmpty()) ? URI.create(url).toURL().getHost() : "");
		} catch (IllegalArgumentException | MalformedURLException e) {
			throw new IllegalArgumentException("Failed to parse Vault URL: " + url, e);
		}
	}

	private void prepareDevModeIfRequired() {
		Config.VaultMode vaultMode = toolConfig().mode();

		if (vaultMode != Config.VaultMode.dev) {
			return;
		}

		log.debug("WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n" + "and starts unsealed with a single unseal key. ");

		Path templatedFile = fileSystemUtils.copyToTempDir(fileSystemUtils.getRootDir() + "/" + VAULT_START_SCRIPT_PATH);
		File postStartScript;
		try {
			postStartScript = new TemplatingEngine().replaceTemplate(
				templatedFile.toFile(), Map.of(
					"namePrefix", toolConfig().namePrefix()
				)
			);
		} catch (Exception e) {
			throw new RuntimeException("Failed to template Vault post-start script", e);
		}

		log.debug("Creating namespace for vault, so it can add its secrets there");
		k8sClient.createNamespace(namespace);

		// Create config map from init script.
		// Init script creates/authorizes secrets, users, service accounts, etc.
		String vaultPostStartConfigMap = "vault-dev-post-start";
		String vaultPostStartVolume = "dev-post-start";
		k8sClient.createConfigMapFromFile(vaultPostStartConfigMap, namespace, postStartScript.getAbsolutePath());

		addHelmValuesData(
			"dev", Map.of(
				"rootToken",
				UUID.randomUUID()
				    .toString(),
				"vaultPostStartConfigMap",
				vaultPostStartConfigMap,
				"vaultPostStartVolume",
				vaultPostStartVolume,
				"postStartScriptName",
				postStartScript.getName()
			)
		);
	}

	private void prepareVaultApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing vault repository content in {}", clusterResourcesRepo.getRepoTarget());

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, VAULT_APP_PATH)
		);
	}

	private void replaceVaultTemplates(GitRepo clusterResourcesRepo) {
		clusterResourcesRepo.replaceTemplates(Map.of("config", toolConfig().templateConfig()));
	}
}
