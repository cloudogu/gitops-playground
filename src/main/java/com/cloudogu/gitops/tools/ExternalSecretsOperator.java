package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.tools.common.CrdBootstrap;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Singleton
@Order(400)
@Slf4j
public class ExternalSecretsOperator extends AbstractMappedTool<ExternalSecretsOperatorToolConfig> implements CrdBootstrap {

	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/external-secrets/templates/values.ftl.yaml";

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TOOL_NAME = "external-secrets";
	private static final String RELEASE_NAME = "external-secrets";
	private static final String EXTERNAL_SECRETS_APP_PATH = "apps/external-secrets";
	private static final String NETWORK_POLICY_TEMPLATE =
		"argocd/cluster-resources/apps/external-secrets/templates/netpols/allow-required-access-to-external-secrets.ftl.yaml";
	private static final String NETWORK_POLICY_PATH =
		"apps/external-secrets/netpols/allow-required-access-to-external-secrets.yaml";

	private static final Set<String> CRD_KINDS = Set.of("CustomResourceDefinition");

	// Kinds that a namespaced ArgoCD cluster registration (see SingleTenantMode) can never sync itself.
	private static final Set<String> OPERATOR_CLUSTER_SCOPED_KINDS = Set.of(
		"ClusterRole", "ClusterRoleBinding", "ValidatingWebhookConfiguration", "MutatingWebhookConfiguration"
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

	@Override
	public void bootstrapCrds(DeploymentContext context) {
		ExternalSecretsOperatorToolConfig config = mapConfig(context);
		if (!isEnabled(config) || config.skipCrds()) {
			return;
		}

		String renderedManifests = renderHelmManifests(config, context, true);
		applyRenderedResources(
			renderedManifests,
			CRD_KINDS,
			"external-secrets CRDs before tool deployment"
		);
	}

	/**
	 * Applies cluster-scoped resources that a namespaced Argo CD cluster registration cannot manage.
	 * CRDs are installed separately during the application bootstrap before Argo CD starts.
	 */
	private void applyClusterScopedResources() {
		if (!toolConfig().operator()) {
			return;
		}

		String renderedManifests = renderHelmManifests(toolConfig(), context, false);
		applyRenderedResources(
			renderedManifests,
			OPERATOR_CLUSTER_SCOPED_KINDS,
			"external-secrets cluster-scoped RBAC and webhook resources"
		);
	}

	private String renderHelmManifests(
		ExternalSecretsOperatorToolConfig config,
		DeploymentContext deploymentContext,
		boolean includeCrds) {
		Map<String, Object> templateData = new HashMap<>();
		templateData.put(
			"statics",
			new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
		);
		templateData.put("config", config.templateConfig());

		Map<String, Object> helmValuesData = new HashMap<>(templateToMap(HELM_VALUES_PATH, templateData));
		MapUtils.deepMerge(config.helm().values(), helmValuesData);
		if (includeCrds) {
			helmValuesData.put("installCRDs", true);
		}
		Path valuesPath = fileSystemUtils.writeTempFile(helmValuesData);

		String chartOrPath;
		Map<String, Object> helmArguments = new HashMap<>();
		helmArguments.put("values", valuesPath.toString());
		helmArguments.put("namespace", config.namespace());

		if (deploymentContext.isAirgapped()) {
			chartOrPath = Path.of(config.helm().localHelmChartFolder(), config.helm().chart()).toString();
		} else {
			helmClient.addRepo(TOOL_NAME, config.helm().repoURL());
			chartOrPath = TOOL_NAME + "/" + config.helm().chart();
			helmArguments.put("version", config.helm().version());
		}

		return helmClient.template(RELEASE_NAME, chartOrPath, helmArguments);
	}

	private void applyRenderedResources(
		String renderedManifests,
		Set<String> resourceKinds,
		String description) {
		String filteredYaml = filterResources(renderedManifests, resourceKinds);
		if (filteredYaml.isBlank()) {
			return;
		}

		Path resourceFile = fileSystemUtils.createTempFile();
		try {
			Files.writeString(resourceFile, filteredYaml);
		} catch (IOException exception) {
			throw new UncheckedIOException(
				"Failed to write " + description + " to temp file",
				exception
			);
		}

		log.debug("Applying {} from path {}", description, resourceFile);
		k8sClient.applyYaml(resourceFile.toString());
	}

	private static String filterResources(String multiDocYaml, Set<String> resourceKinds) {
		StringBuilder filtered = new StringBuilder();

		for (String document : multiDocYaml.split("(?m)^---\\s*$")) {
			if (document.isBlank() || !resourceKinds.contains(kindOf(document))) {
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
			log.error("Failed to determine Kubernetes resource kind", exception);
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
}
