package com.cloudogu.gitops.tools.ingress;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.dataformat.yaml.YAMLMapper;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Singleton
@Order(150)
@Slf4j
public class Ingress extends AbstractMappedTool<IngressToolConfig> implements CrdBootstrap {

	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/traefik/templates/values.ftl.yaml";

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TOOL_NAME = "traefik";
	private static final String RELEASE_NAME = "traefik";
	private static final String INGRESS_APP_PATH = "apps/traefik";
	private static final String NETWORK_POLICY_TEMPLATE =
		"argocd/cluster-resources/apps/traefik/templates/netpols/allow-required-access-to-traefik.ftl.yaml";
	private static final String NETWORK_POLICY_PATH =
		"apps/traefik/netpols/allow-required-access-to-traefik.yaml";

	// Gateway API CRDs are no longer shipped with chart!
	private static final String GATEWAY_API_CRD_VERSION = "v1.6.1";
	private static final String GATEWAY_API_CRD = "https://github.com/kubernetes-sigs/gateway-api/releases/download/" + GATEWAY_API_CRD_VERSION + "/standard-install.yaml";
	private static final String GATEWAY_ADMISSION_API_VERSION = "admissionregistration.k8s.io/v1";
	private static final String GATEWAY_ADMISSION_NAME = "safe-upgrades.gateway.networking.k8s.io";
	private static final String POLICY_KIND = "ValidatingAdmissionPolicy";
	private static final String BINDING_KIND = "ValidatingAdmissionPolicyBinding";

	private static final Pattern CLUSTER_RBAC_KIND = Pattern.compile("(?m)^kind: (ClusterRole|ClusterRoleBinding)\\s*$");
	private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder().build();

	private final ImagePullSecretCreator imagePullSecretCreator;
	private final K8sClient k8sClient;
	private final HelmClient helmClient;

	@Getter
	@Setter
	private String namespace;

	public Ingress(
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator,
		IngressToolConfigMapper configMapper,
		K8sClient k8sClient,
		HelmClient helmClient) {
		super(configMapper);
		this.deployer = deployer;
		this.fileSystemUtils = fileSystemUtils;
		this.airGappedUtils = airGappedUtils;
		this.gitHandler = gitHandler;
		this.imagePullSecretCreator = imagePullSecretCreator;
		this.k8sClient = k8sClient;
		this.helmClient = helmClient;
	}

	@Override
	protected boolean isEnabled(IngressToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		this.namespace = activeNamespace(toolConfig());

		createImagePullSecret();
		applyGatewayApiAdmissionPolicies();
		applyClusterRbac();
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

	private void applyGatewayApiAdmissionPolicies() {
		IngressToolConfig config = toolConfig();
		if (!isEnabled(config) || config.skipCrds()) {
			return;
		}

		String source = gatewayApiBundleLocation(config);
		Map<String, JsonNode> admissionResources = new HashMap<>();
		Path manifest;
		try (InputStream stream = openGatewayApiBundle(source, config.airgapped());
			 MappingIterator<JsonNode> documents = YAML_MAPPER.readerFor(JsonNode.class).readValues(stream)) {
			while (documents.hasNextValue()) {
				JsonNode document = documents.nextValue();
				String kind = document.path("kind").asText();
				if ((POLICY_KIND.equals(kind) || BINDING_KIND.equals(kind))
					&& GATEWAY_ADMISSION_API_VERSION.equals(document.path("apiVersion").asText())
					&& GATEWAY_ADMISSION_NAME.equals(document.path("metadata").path("name").asText())) {
					admissionResources.put(kind, document);
				}
			}

			for (String kind : new String[]{POLICY_KIND, BINDING_KIND}) {
				if (!admissionResources.containsKey(kind)) {
					throw new IllegalStateException(
						"Gateway API bundle %s is missing %s %s named %s"
							.formatted(source, GATEWAY_ADMISSION_API_VERSION, kind, GATEWAY_ADMISSION_NAME)
					);
				}
			}

			manifest = fileSystemUtils.createTempFile();
			Files.writeString(
				manifest,
				YAML_MAPPER.writeValueAsString(admissionResources.get(POLICY_KIND))
					+ YAML_MAPPER.writeValueAsString(admissionResources.get(BINDING_KIND))
			);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Failed to parse Gateway API admission policies from " + source, exception);
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to prepare Gateway API admission policies from " + source, exception);
		}

		log.debug("Applying Gateway API admission policy and binding from path {}", manifest);
		k8sClient.applyYaml(manifest.toString());
	}

	private static InputStream openGatewayApiBundle(String source, boolean airgapped) throws IOException {
		if (airgapped) {
			return Files.newInputStream(Path.of(source));
		}
		URLConnection connection = URI.create(source).toURL().openConnection();
		connection.setConnectTimeout(10_000);
		connection.setReadTimeout(60_000);
		return connection.getInputStream();
	}

	/**
	 * In operator mode Argo CD is registered namespaced and cannot sync cluster-scoped resources.
	 * Without the ClusterRole Traefik cannot watch Ingresses and answers every request with 404.
	 */
	private void applyClusterRbac() {
		if (!toolConfig().operator()) {
			return;
		}

		Map<String, Object> templateData = new HashMap<>();
		templateData.put(
			"statics",
			new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()
		);
		templateData.put("config", toolConfig().templateConfig());
		Map<String, Object> helmValues = new HashMap<>(templateToMap(HELM_VALUES_PATH, templateData));
		MapUtils.deepMerge(toolConfig().helm().values(), helmValues);

		Map<String, Object> helmArguments = new HashMap<>();
		helmArguments.put("values", fileSystemUtils.writeTempFile(helmValues).toString());
		helmArguments.put("namespace", namespace);
		// helm template has no cluster access; the chart fails rendering its ServiceMonitor without this API
		helmArguments.put("api-versions", "monitoring.coreos.com/v1");
		String chartOrPath;
		if (toolConfig().airgapped()) {
			chartOrPath = Path.of(toolConfig().helm().localHelmChartFolder(), toolConfig().helm().chart()).toString();
		} else {
			helmClient.addRepo(TOOL_NAME, toolConfig().helm().repoURL());
			chartOrPath = TOOL_NAME + "/" + toolConfig().helm().chart();
			helmArguments.put("version", toolConfig().helm().version());
		}

		// Split the YAML stream into documents so each resource can be filtered by kind.
		String extractFromInlineMultiYamls = "(?m)^---\\s*$";
		String clusterRbac = Arrays.stream(helmClient.template(RELEASE_NAME, chartOrPath, helmArguments).split(
									   extractFromInlineMultiYamls))
								   .filter(document -> CLUSTER_RBAC_KIND.matcher(document).find())
								   .collect(Collectors.joining("\n---\n"));

		Path rbacFile = fileSystemUtils.createTempFile();
		try {
			Files.writeString(rbacFile, clusterRbac);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write Traefik cluster RBAC to temp file", e);
		}
		log.debug("Applying Traefik ClusterRole and ClusterRoleBinding from path {}", rbacFile);
		k8sClient.applyYaml(rbacFile.toString());
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

	@Override
	public void bootstrapCrds(DeploymentContext context) {
		IngressToolConfig config = mapConfig(context);
		if (!isEnabled(config) || config.skipCrds()) {
			return;
		}

		String source = gatewayApiBundleLocation(config);
		log.debug("Bootstrapping Gateway API CRDs before tool deployment from {}", source);
		k8sClient.createMissingCrdsAndWait(source);
	}

	String gatewayApiBundleLocation(IngressToolConfig config) {
		if (config.airgapped()) {
			return Path.of(
				config.helm().localHelmChartFolder(),
				config.helm().chart(),
				"charts/traefik/crds/gateway-api-standard-install.yaml"
			).toString();
		}

		return GATEWAY_API_CRD;
	}
}
