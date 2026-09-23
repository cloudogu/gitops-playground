package com.cloudogu.gitops.tools.externalsecrets;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Singleton
@Order(400)
@Slf4j
public class ExternalSecretsOperator extends AbstractMappedTool<ExternalSecretsOperatorToolConfig> {

	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/external-secrets/templates/values.ftl.yaml";

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TOOL_NAME = "external-secrets";
	private static final String RELEASE_NAME = "external-secrets";
	private static final String EXTERNAL_SECRETS_APP_PATH = "apps/external-secrets";
	private static final String NETWORK_POLICY_TEMPLATE =
		"argocd/cluster-resources/apps/external-secrets/templates/netpols/allow-required-access-to-external-secrets.ftl.yaml";
	private static final String NETWORK_POLICY_PATH =
		"apps/external-secrets/netpols/allow-required-access-to-external-secrets.yaml";
	private static final String EXTERNAL_VAULT_RESOURCES_TEMPLATE =
		"argocd/cluster-resources/apps/external-secrets/templates/external-vault-resources.ftl.yaml";
	private static final String EXTERNAL_VAULT_RESOURCES_PATH =
		"apps/external-secrets/misc/external-vault-resources.yaml";

	// Kinds that a namespaced ArgoCD cluster registration (see SingleTenantMode) can never sync itself.
	private static final Set<String> CLUSTER_SCOPED_KINDS = Set.of(
		"CustomResourceDefinition", "ClusterRole", "ClusterRoleBinding",
		"ValidatingWebhookConfiguration", "MutatingWebhookConfiguration"
	);
	private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};

	private final ImagePullSecretCreator imagePullSecretCreator;
	private final K8sClient k8sClient;
	private final HelmClient helmClient;

	@Getter
	@Setter
	private String namespace;

	public ExternalSecretsOperator(
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		HelmClient helmClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator,
		ExternalSecretsOperatorToolConfigMapper configMapper) {
		super(configMapper);
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.k8sClient = k8sClient;
		this.helmClient = helmClient;
		this.airGappedUtils = airGappedUtils;
		this.gitHandler = gitHandler;
		this.imagePullSecretCreator = imagePullSecretCreator;
	}

	@Override
	protected boolean isEnabled(ExternalSecretsOperatorToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		this.namespace = activeNamespace(toolConfig());

		createImagePullSecret();
		applyClusterScopedResources();
		prepareExternalSecretsApp(repositoryWorkspace.getClusterResourcesRepository());
		prepareExternalSecretsNetworkPolicy(repositoryWorkspace.getClusterResourcesRepository());
		prepareExternalVaultResources(repositoryWorkspace.getClusterResourcesRepository());
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
	protected String activeNamespace(ExternalSecretsOperatorToolConfig config) {
		return config.namespace();
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(toolConfig().imagePullSecret(), namespace);
	}

	/**
	 * Renders the external-secrets Helm chart and applies its cluster-scoped resources (CRDs, ClusterRoles,
	 * ClusterRoleBindings, webhook configurations) imperatively. Argo CD fails to sync these when the
	 * in-cluster target is registered as a namespaced cluster (operator mode). Chicken-egg-problem.
	 */
	private void applyClusterScopedResources() {
		if (!toolConfig().operator() || toolConfig().skipCrds()) {
			return;
		}

		addHelmValuesData(
			"statics",
			new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
		);
		addHelmValuesData("config", toolConfig().templateConfig());
		Map<String, Object> helmValuesData = templateToMap(HELM_VALUES_PATH, this.helmValuesTemplateData);
		helmValuesData = MapUtils.deepMerge(toolConfig().helm().values(), helmValuesData);
		Path valuesPath = fileSystemUtils.writeTempFile(helmValuesData);

		helmClient.addRepo(TOOL_NAME, toolConfig().helm().repoURL());
		String renderedManifests = helmClient.template(
			RELEASE_NAME,
			TOOL_NAME + "/" + toolConfig().helm().chart(),
			Map.of(
				"version", toolConfig().helm().version(),
				"values", valuesPath.toString(),
				"namespace", namespace
			)
		);

		String clusterScopedYaml = filterClusterScopedResources(renderedManifests);
		if (clusterScopedYaml.isBlank()) {
			return;
		}

		Path clusterScopedFile = fileSystemUtils.createTempFile();
		try {
			Files.writeString(clusterScopedFile, clusterScopedYaml);
		} catch (IOException exception) {
			throw new UncheckedIOException(
				"Failed to write cluster-scoped resources for external-secrets to temp file",
				exception
			);
		}

		log.debug(
			"Applying cluster-scoped resources (CRDs, ClusterRoles, ClusterRoleBindings, webhooks) for " +
				"external-secrets; Argo CD fails to sync them when running in namespaced (operator) mode. " +
				"Chicken-egg-problem.\nApplying from path {}",
			clusterScopedFile
		);
		k8sClient.applyYaml(clusterScopedFile.toString());
	}

	/**
	 * Filters a rendered, multi-document Helm YAML string down to the cluster-scoped resource kinds
	 * (CRDs, ClusterRoles, ClusterRoleBindings, webhook configurations) that must be applied imperatively,
	 * since ArgoCD cannot manage them when the in-cluster target is registered as a namespaced cluster.
	 */
	private static String filterClusterScopedResources(String multiDocYaml) {
		StringBuilder filtered = new StringBuilder();

		for (String document : multiDocYaml.split("(?m)^---\\s*$")) {
			if (document.isBlank() || !CLUSTER_SCOPED_KINDS.contains(kindOf(document))) {
				continue;
			}
			filtered.append("---\n").append(document.strip()).append("\n");
		}

		return filtered.toString();
	}

	private static String kindOf(String yamlDocument) {
		try {
			Map<String, Object> parsed = YAML_MAPPER.readValue(yamlDocument, YAML_MAP_TYPE);
			Object kind = parsed == null ? null : parsed.get("kind");
			return kind == null ? "" : kind.toString();
		} catch (IOException exception) {
			log.error("IOException occured");
			return "";
		}
	}

	private void prepareExternalSecretsApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing external-secrets repository content in {}", clusterResourcesRepo.getRepoTarget());

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, EXTERNAL_SECRETS_APP_PATH)
		);
	}

	private void prepareExternalSecretsNetworkPolicy(GitRepo clusterResourcesRepo) {
		Path networkPolicyPath = Path.of(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), NETWORK_POLICY_PATH);
		if (!toolConfig().netpols()) {
			FileSystemUtils.deleteFile(networkPolicyPath.toString());
			return;
		}

		try {
			String networkPolicyYaml = new TemplatingEngine().template(
				new File(NETWORK_POLICY_TEMPLATE),
				Map.of("namespace", namespace)
			);
			clusterResourcesRepo.writeFile(NETWORK_POLICY_PATH, networkPolicyYaml);
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate external-secrets NetworkPolicy", e);
		}
	}

	private void prepareExternalVaultResources(GitRepo clusterResourcesRepo) {
		Path resourcesPath = Path.of(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), EXTERNAL_VAULT_RESOURCES_PATH);
		if (toolConfig().managedSecrets().isEmpty()) {
			FileSystemUtils.deleteFile(resourcesPath.toString());
			return;
		}

		ExternalVaultConfig vault = toolConfig().externalVault();
		Map<String, Object> vaultTemplateData = Map.of(
			"storeName", vault.storeName(),
			"server", vault.server(),
			"path", vault.path(),
			"version", vault.version(),
			"tokenSecretName", vault.tokenSecretName(),
			"tokenSecretKey", vault.tokenSecretKey(),
			"targetNamespaces", vault.targetNamespaces()
		);
		var secretTemplateData = toolConfig().managedSecrets().stream()
			.map(secret -> Map.<String, Object>of(
				"name", secret.name(),
				"namespace", secret.namespace(),
				"remoteKey", secret.remoteKey(),
				"data", secret.data()
			))
			.toList();

		try {
			String resourcesYaml = new TemplatingEngine().template(
				new File(EXTERNAL_VAULT_RESOURCES_TEMPLATE),
				Map.of(
					"vault", vaultTemplateData,
					"externalSecrets", secretTemplateData
				)
			);
			clusterResourcesRepo.writeFile(EXTERNAL_VAULT_RESOURCES_PATH, resourcesYaml);
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate external Vault resources", e);
		}
	}
}
