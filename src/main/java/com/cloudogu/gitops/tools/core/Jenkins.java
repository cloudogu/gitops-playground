package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager;
import com.cloudogu.gitops.infrastructure.jenkins.JobManager;
import com.cloudogu.gitops.infrastructure.jenkins.PrometheusConfigurator;
import com.cloudogu.gitops.infrastructure.jenkins.UserManager;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractMappedTool;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.CommandExecutor;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.NetworkingUtils;
import com.cloudogu.gitops.utils.Tuple;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Singleton
@Order(200)
@Slf4j
public class Jenkins extends AbstractMappedTool<JenkinsToolConfig> {

	public static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/jenkins/templates/values.ftl.yaml";

	private static final List<String> OIDC_BOOT_PLUGIN_NAMES = Arrays.asList(
		"oic-auth",
		"json-path-api",
		"matrix-auth"
	);

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TOOL_NAME = "jenkins";
	private static final String ETC_GROUP_PATH = "/etc/group";
	private static final String JENKINS_APP_PATH = "apps/jenkins";
	private static final int PLUGIN_NAME_SPLIT_LIMIT = 2;
	private static final int GID_GREPPER_POD_SUFFIX_BOUND = 10_000;
	private static final int ETC_GROUP_MIN_FIELDS = 3;
	private static final int ETC_GROUP_GID_FIELD_INDEX = 2;
	// Not security-sensitive: only used to make a temporary pod name unique.
	private static final Random RANDOM = new Random();

	@Getter
	@Setter
	private String namespace;
	private final CommandExecutor commandExecutor;
	private final GlobalPropertyManager globalPropertyManager;
	private final JobManager jobManager;
	private final UserManager userManager;
	private final PrometheusConfigurator prometheusConfigurator;

	private final ImagePullSecretCreator imagePullSecretCreator;
	private final K8sClient k8sClient;
	private final NetworkingUtils networkingUtils;
	private final JenkinsConfigUpdater configUpdater;
	private String runtimeUrl;

	public Jenkins(
		CommandExecutor commandExecutor,
		FileSystemUtils fileSystemUtils,
		GlobalPropertyManager globalPropertyManager,
		JobManager jobManager,
		UserManager userManager,
		PrometheusConfigurator prometheusConfigurator,
		Deployer deployer,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator,
		JenkinsToolConfigMapper configMapper,
		JenkinsConfigUpdater configUpdater) {
		super(configMapper);
		this.commandExecutor = commandExecutor;
		this.fileSystemUtils = fileSystemUtils;
		this.globalPropertyManager = globalPropertyManager;
		this.jobManager = jobManager;
		this.userManager = userManager;
		this.prometheusConfigurator = prometheusConfigurator;
		this.deployer = deployer;
		this.k8sClient = k8sClient;
		this.networkingUtils = networkingUtils;
		this.airGappedUtils = airGappedUtils;
		this.gitHandler = gitHandler;
		this.imagePullSecretCreator = imagePullSecretCreator;
		this.configUpdater = configUpdater;
	}

	@Override
	protected boolean isEnabled(JenkinsToolConfig config) {
		return config.active();
	}

	@Override
	protected void preDeploy() {
		this.runtimeUrl = toolConfig().server().url();
		if (!isInternalJenkins()) {
			return;
		}

		this.namespace = activeNamespace(toolConfig());

		createImagePullSecret();
		createJenkinsNamespace();
		labelJenkinsNode();
		createJenkinsCredentialsSecret();
		prepareJenkinsHelmValues();
		prepareJenkinsApp(repositoryWorkspace.getClusterResourcesRepository());
	}

	@Override
	protected void deploy() {
		if (!isInternalJenkins()) {
			return;
		}

		deployInternalJenkins();
	}

	@Override
	protected void postDeploy() {
		if (isInternalJenkins()) {
			updateJenkinsUrl();
		}

		runSetupScript();
	}

	@Override
	protected void publishChanges() {
		if (!isInternalJenkins()) {
			return;
		}

		publishClusterResourcesChanges(TOOL_NAME);
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(toolConfig().imagePullSecret(), namespace);
	}

	private void createJenkinsNamespace() {
		k8sClient.createNamespace(namespace);
	}

	private void labelJenkinsNode() {
		// Mark the first node for Jenkins and agents. See jenkins/values.ftl.yaml "agent.workingDir"
		// for details.
		// Remove first in case new nodes were added.
		k8sClient.labelRemove("node", "--all", "", "node");

		String nodeName = k8sClient.waitForNode().replace("node/", "");
		k8sClient.label("node", nodeName, new Tuple<>("node", TOOL_NAME));
	}

	private void createJenkinsCredentialsSecret() {
		k8sClient.createSecret(
			"generic", "jenkins-credentials", namespace, new Tuple<>(
				"jenkins-admin-user", toolConfig().server().username()
			), new Tuple<>(
				"jenkins-admin-password", toolConfig().server().password()
			)
		);
	}

	private void prepareJenkinsHelmValues() {
		addHelmValuesData("dockerGid", findDockerGid());
		addHelmValuesData(
			"jenkinsBootPlugins",
			jenkinsOidcConfigured() ? getJenkinsOidcBootPlugins() : Collections.emptyList()
		);
	}

	@Override
	protected String activeNamespace(JenkinsToolConfig config) {
		return config.namespace();
	}

	private boolean isInternalJenkins() {
		return toolConfig().internal();
	}

	private void deployInternalJenkins() {
		addHelmValuesData("config", toolConfig().templateConfig());
		deployHelmChart(TOOL_NAME, TOOL_NAME, namespace, toolConfig().helm(), HELM_VALUES_PATH, context, true);
	}

	private void updateJenkinsUrl() {
		// Defined here:
		// https://github.com/jenkinsci/helm-charts/blob/jenkins-5.8.1/charts/jenkins/templates/_helpers.tpl#L46-L57
		String serviceName = TOOL_NAME;

		// Update jenkins.url after it is deployed and ports are known.
		if (toolConfig().application().runningInsideK8s()) {
			log.debug("Setting jenkins url to k8s service, since installation is running inside k8s");
			runtimeUrl = networkingUtils.createUrl(serviceName + "." + namespace + ".svc.cluster.local", "80");
			configUpdater.updateUrl(context, runtimeUrl);
		} else {
			log.debug(
				"Setting jenkins configs for local single node cluster with internal jenkins. Waiting for NodePort...");
			String port = k8sClient.waitForNodePort(serviceName, namespace);
			String clusterBindAddress = networkingUtils.findClusterBindAddress();
			runtimeUrl = networkingUtils.createUrl(clusterBindAddress, port);
			configUpdater.updateUrl(context, runtimeUrl);
		}
	}

	private void prepareJenkinsApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing Jenkins repository content in {}", clusterResourcesRepo.getRepoTarget());

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, JENKINS_APP_PATH)
		);
	}

	private void runSetupScript() {
		Map<String, Object> scriptParams = new HashMap<>();
		scriptParams.put("TRACE", toolConfig().application().trace());
		scriptParams.put("INTERNAL_JENKINS", toolConfig().internal());
		scriptParams.put("JENKINS_HELM_CHART_VERSION", toolConfig().helm().version());
		scriptParams.put("JENKINS_URL", runtimeUrl);
		scriptParams.put("JENKINS_USERNAME", toolConfig().server().username());
		scriptParams.put("JENKINS_PASSWORD", toolConfig().server().password());
		scriptParams.put("SCM_URL", this.gitHandler.getTenant().getUrl());
		scriptParams.put("PREFIXED_SCM_URL", this.gitHandler.getTenant().repoPrefix());
		scriptParams.put("SCM_PASSWORD", this.gitHandler.getTenant().getCredentials().getPassword());
		scriptParams.put("SCM_PROVIDER", toolConfig().scm().providerType());
		scriptParams.put("INSTALL_ARGOCD", toolConfig().argocdActive());
		scriptParams.put("NAME_PREFIX", toolConfig().application().namePrefix());
		scriptParams.put("INSECURE", toolConfig().application().insecure());
		scriptParams.put("SKIP_RESTART", toolConfig().server().skipRestart());
		scriptParams.put("SKIP_PLUGINS", toolConfig().server().skipPlugins());

		commandExecutor.execute(fileSystemUtils.getRootDir() + "/scripts/jenkins/init-jenkins.sh", scriptParams);

		configureGlobalProperties();
		configureMetricsUser();
	}

	private void configureGlobalProperties() {
		setPrefixedGlobalProperty("SCM_URL", this.gitHandler.getTenant().getUrl());
		setPrefixedGlobalProperty("PREFIXED_SCM_URL", this.gitHandler.getTenant().repoPrefix());

		if (!toolConfig().server().additionalEnvironments().isEmpty()) {
			for (Map.Entry<String, String> entry : toolConfig().server().additionalEnvironments().entrySet()) {
				globalPropertyManager.setGlobalProperty(entry.getKey(), entry.getValue());
			}
		}

		setPrefixedGlobalPropertyIfNotEmpty("REGISTRY_URL", toolConfig().registry().url());
		setPrefixedGlobalPropertyIfNotEmpty("REGISTRY_PATH", toolConfig().registry().path());

		if (toolConfig().registry().twoRegistries()) {
			setPrefixedGlobalProperty("REGISTRY_PROXY_URL", toolConfig().registry().proxyUrl());
			setPrefixedGlobalProperty("REGISTRY_PROXY_PATH", toolConfig().registry().proxyPath());
		}

		setPrefixedGlobalPropertyIfNotEmpty("MAVEN_CENTRAL_MIRROR", toolConfig().server().mavenCentralMirror());

		setPrefixedGlobalProperty("K8S_VERSION", Config.K8S_VERSION);
	}

	private void configureMetricsUser() {
		if (userManager.isUsingSecurityRealmWithoutLocalUserCreation()) {
			log.trace("Using a security realm without local user creation. Must not create user.");
		} else {
			userManager.createUser(
				toolConfig().server().metricsUsername(), toolConfig().server().metricsPassword()
			);
		}

		userManager.grantPermission(
			toolConfig().server().metricsUsername(), UserManager.Permissions.METRICS_VIEW
		);

		if (toolConfig().monitoringActive() && toolConfig().internal()) {
			// An external Jenkins can likely not be monitored
			prometheusConfigurator.enableAuthentication();
		}
	}

	private void setPrefixedGlobalProperty(String name, String value) {
		globalPropertyManager.setGlobalProperty(toolConfig().application().environmentPrefix() + name, value);
	}

	private void setPrefixedGlobalPropertyIfNotEmpty(String name, String value) {
		if (StringUtils.isNotEmpty(value)) {
			setPrefixedGlobalProperty(name, value);
		}
	}

	public void createJenkinsjob(String namespace, String repoName) {
		String credentialId = "scm-user";
		String prefixedNamespace = toolConfig().application().namePrefix() + namespace;
		String jobName = toolConfig().application().namePrefix() + repoName;

		jobManager.createJob(jobName, this.gitHandler.getTenant().getUrl(), prefixedNamespace, credentialId);

		if (toolConfig().scm().providerType() == ScmProviderType.SCM_MANAGER) {
			jobManager.createCredential(
				jobName,
				credentialId,
				toolConfig().application().namePrefix() + "gitops",
				toolConfig().scm().scmManagerPassword(),
				"credentials for accessing scm-manager"
			);
		}

		if (toolConfig().scm().providerType() == ScmProviderType.GITLAB) {
			jobManager.createCredential(
				jobName,
				credentialId,
				toolConfig().scm().gitlabUsername(),
				toolConfig().scm().gitlabPassword(),
				"credentials for accessing gitlab"
			);
		}

		jobManager.createCredential(
			jobName,
			"registry-user",
			toolConfig().registry().username(),
			toolConfig().registry().password(),
			"credentials for accessing the docker-registry for writing images built on jenkins"
		);

		if (toolConfig().registry().twoRegistries()) {
			jobManager.createCredential(
				jobName,
				"registry-proxy-user",
				toolConfig().registry().proxyUsername(),
				toolConfig().registry().proxyPassword(),
				"credentials for accessing the docker-registry that contains 3rd party or base images"
			);
		}

		jobManager.startJob(jobName);
	}

	private boolean jenkinsOidcConfigured() {
		return toolConfig().server().oidcConfigured();
	}

	private List<String> getJenkinsOidcBootPlugins() {
		File pluginsFile = new File(fileSystemUtils.getRootDir() + "/scripts/jenkins/plugins/plugins.txt");
		Map<String, String> pinnedPlugins = new HashMap<>();

		try {
			List<String> lines = Files.readAllLines(pluginsFile.toPath());
			for (String line : lines) {
				String pluginDefinition = line.trim();
				if (pluginDefinition.isEmpty() || pluginDefinition.startsWith("#")) {
					continue;
				}
				String pluginName = pluginDefinition.split(":", PLUGIN_NAME_SPLIT_LIMIT)[0];
				if (OIDC_BOOT_PLUGIN_NAMES.contains(pluginName)) {
					pinnedPlugins.put(pluginName, pluginDefinition);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read plugins file: " + pluginsFile, e);
		}

		List<String> missingPlugins = OIDC_BOOT_PLUGIN_NAMES.stream()
		                                                    .filter(name -> !pinnedPlugins.containsKey(name))
		                                                    .toList();

		if (!missingPlugins.isEmpty()) {
			throw new IllegalStateException("Required Jenkins OIDC boot plugins missing from " + pluginsFile + ": " + String.join(
				", ",
				missingPlugins
			));
		}

		List<String> result = new ArrayList<>();
		for (String name : OIDC_BOOT_PLUGIN_NAMES) {
			result.add(pinnedPlugins.get(name));
		}
		return result;
	}

	protected String findDockerGid() {
		String gid = "";
		String etcGroup = k8sClient.run(
			"tmp-docker-gid-grepper-" + RANDOM.nextInt(GID_GREPPER_POD_SUFFIX_BOUND),
			"irrelevant" /* Redundant, but mandatory param */,
			namespace,
			createGidGrepperOverrides(),
			"--restart=Never",
			"-ti",
			"--rm",
			"--quiet"
		);

		if (etcGroup != null) {
			String[] lines = etcGroup.split("\n");
			for (String line : lines) {
				String[] parts = line.split(":");
				if (parts.length >= ETC_GROUP_MIN_FIELDS && "docker".equals(parts[0])) {
					gid = parts[ETC_GROUP_GID_FIELD_INDEX];
					break;
				}
			}
		}

		if (gid.isEmpty()) {
			log.warn(
				"""
					Unable to determine Docker Group ID (GID). Jenkins Agent pods will run as root user (UID 0)!
					Group docker not found in /etc/group:
					{}""", etcGroup
			);
			return "";
		} else {
			log.debug("Using Docker Group ID (GID) {} for Jenkins Agent pods", gid);
			return gid;
		}
	}

	Map<String, Object> createGidGrepperOverrides() {
		return Map.of(
			"spec", Map.of(
				"containers",
				List.of(Map.of(
					"name",
					"tmp-docker-gid-grepper",
					"image",
					toolConfig().server().internalBashImage(),
					"args",
					List.of("cat", ETC_GROUP_PATH),
					"volumeMounts",
					List.of(Map.of("name", "group", "mountPath", ETC_GROUP_PATH, "readOnly", true))
				)),
				"nodeSelector",
				Map.of("node", TOOL_NAME),
				"volumes",
				List.of(Map.of("name", "group", "hostPath", Map.of("path", ETC_GROUP_PATH)))
			)
		);
	}

}
