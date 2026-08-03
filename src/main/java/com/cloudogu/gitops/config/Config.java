package com.cloudogu.gitops.config;

import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.cloudogu.gitops.config.ConfigConstants.APPLICATION_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.APPLICATION_GOP_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.APPLICATION_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.APPLICATION_PROFIL;
import static com.cloudogu.gitops.config.ConfigConstants.APP_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_EMAIL_FROM_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_EMAIL_TO_USER_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_ENV_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_OPERATOR_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_RESOURCE_INCLUSIONS_CLUSTER;
import static com.cloudogu.gitops.config.ConfigConstants.ARGOCD_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.BASE_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.BINARY_NAME;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CLUSTER_ADMIN_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONFIG_FILE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONFIG_MAP_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_CHART_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_NAMESPACE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_NAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_RELEASE_NAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_REPO_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_VALUES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_VALUES_FILE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_HELM_RELEASE_VERSION_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_NAMESPACES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREATE_JENKINS_JOB_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_CREDENTIALS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_PATH_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_REF_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_TARGET_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_TARGET_OVERWRITE_MODE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_TARGET_REF_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_TEMPLATING_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_TYPE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_REPO_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_STATICSWHITELIST_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.CONTENT_VARIABLES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.DEBUG_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.DESTROY_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.ESO_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.EXTERNAL_SECRETS_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.FEATURES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GIT_EMAIL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GIT_NAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GRAFANA_EMAIL_FROM_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GRAFANA_EMAIL_TO_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GRAFANA_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GRAFANA_SIDECAR_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.GRAFANA_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_CHART_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_REPO_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_VALUES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_VERSION_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.INGRESS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.INGRESS_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.INGRESS_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.INSECURE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_ADDITIONAL_ENVS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_METRICS_PASSWORD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_METRICS_USERNAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_PASSWORD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_SKIP_PLUGINS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_SKIP_RESTART_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.JENKINS_USERNAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.MAIL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.MAVEN_CENTRAL_MIRROR_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.MIRROR_REPOS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.MONITORING_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.MONITORING_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.MONITORING_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.MULTITENANT_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.NAMESPACE_ISOLATION_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.NAME_PREFIX_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.NETPOLS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.OIDC_DESCPRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.OPENSHIFT_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.OUTPUT_CONFIG_FILE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.PASSWORD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.PIPE_YES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.POD_RESOURCES_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.PROMETHEUS_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_INTERNAL_PORT_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PASSWORD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PASSWORD_RO_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PATH_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PROXY_PASSWORD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PROXY_PATH_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PROXY_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_PROXY_USERNAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_URL_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_USERNAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.REGISTRY_USERNAME_RO_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SCM_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SECRETS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SECRETS_NAMESPACE;
import static com.cloudogu.gitops.config.ConfigConstants.SKIP_CRDS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SMTP_ADDRESS_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SMTP_PASSWORD_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SMTP_PORT_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.SMTP_USER_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.TRACE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.URL_SEPARATOR_HYPHEN_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.USERNAME_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.VAULT_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.VAULT_ENABLE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.VAULT_IMAGE_DESCRIPTION;
import static com.cloudogu.gitops.config.ConfigConstants.VAULT_URL_DESCRIPTION;
import static picocli.CommandLine.ScopeType;

@Singleton
@Command(name = BINARY_NAME, description = APP_DESCRIPTION)
@Getter
@Setter
public class Config {

	// When updating please also update in Dockerfile
	public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:4.2.1-1";
	// When updating please also adapt in Dockerfile, vars.tf and init-cluster.sh
	public static final String K8S_VERSION = "1.36.2";
	public static final String DEFAULT_ADMIN_USER = "admin";

	public static final String DEFAULT_ADMIN_PW = generatePassword();

	public static final int DEFAULT_REGISTRY_PORT = 30000;
	private static final int GENERATED_PASSWORD_LENGTH = 12;

	private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new SimpleModule().addSerializer(groovy.lang.GString.class, new JsonSerializer<groovy.lang.GString>() {
		@Override
		public void serialize(groovy.lang.GString value,
		                      JsonGenerator jsonGenerator,
		                      SerializerProvider serializerProvider) throws IOException {
			jsonGenerator.writeString(value.toString());
		}
	}));

	@JsonPropertyDescription(REGISTRY_DESCRIPTION)
	@Mixin
	private RegistrySchema registry = new RegistrySchema();

	@JsonPropertyDescription(JENKINS_DESCRIPTION)
	@Mixin
	private JenkinsSchema jenkins = new JenkinsSchema();

	@JsonPropertyDescription(MULTITENANT_DESCRIPTION)
	@Mixin
	private MultiTenantSchema multiTenant = new MultiTenantSchema();

	@JsonPropertyDescription(SCM_DESCRIPTION)
	@Mixin
	private ScmTenantSchema scm = new ScmTenantSchema();

	@JsonPropertyDescription(APPLICATION_DESCRIPTION)
	@Mixin
	private ApplicationSchema application = new ApplicationSchema();

	@JsonPropertyDescription(FEATURES_DESCRIPTION)
	@Mixin
	private FeaturesSchema features = new FeaturesSchema();

	@JsonPropertyDescription(CONTENT_DESCRIPTION)
	@Mixin
	private ContentSchema content = new ContentSchema();

	private static String generatePassword() {
		SecureRandom sr = new SecureRandom();
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@$%&";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
			sb.append(chars.charAt(sr.nextInt(chars.length())));
		}
		return sb.toString();
	}

	@Getter
	@Setter
	public static class ContentSchema {
		@JsonPropertyDescription(CONTENT_NAMESPACES_DESCRIPTION)
		private List<String> namespaces = new ArrayList<>();

		@JsonPropertyDescription(CONTENT_REPO_DESCRIPTION)
		private List<ContentRepositorySchema> repos = new ArrayList<>();

		@JsonPropertyDescription(CONTENT_VARIABLES_DESCRIPTION)
		private Map<String, Object> variables = new HashMap<>();

		@JsonPropertyDescription()
		private List<HelmReleaseSchema> helmReleases = new ArrayList<>();

		@Option(names = {"--content-whitelist"}, description = CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION)
		@JsonPropertyDescription(CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION)
		private Boolean useWhitelist = false;

		@JsonPropertyDescription(CONTENT_STATICSWHITELIST_DESCRIPTION)
		private Set<String> allowedStaticsWhitelist = new HashSet<>(Arrays.asList("java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Math", "com.cloudogu.gitops.utils.DockerImageParser"));

		@Getter
		@Setter
		@NoArgsConstructor
		public static class ContentRepositorySchema {
			public static final String DEFAULT_PATH = ".";
			public static final ContentRepoType DEFAULT_TYPE = ContentRepoType.MIRROR;

			@JsonPropertyDescription(CONTENT_REPO_URL_DESCRIPTION)
			private String url = "";

			@JsonPropertyDescription(CONTENT_REPO_PATH_DESCRIPTION)
			private String path = DEFAULT_PATH;

			@JsonPropertyDescription(CONTENT_REPO_REF_DESCRIPTION)
			private String ref = "";

			@JsonPropertyDescription(CONTENT_REPO_TARGET_REF_DESCRIPTION)
			private String targetRef = "";

			@JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
			private Credentials credentials;

			@JsonPropertyDescription(CONTENT_REPO_TEMPLATING_DESCRIPTION)
			private Boolean templating = false;

			@JsonPropertyDescription(CONTENT_REPO_TYPE_DESCRIPTION)
			private ContentRepoType type = DEFAULT_TYPE;

			@JsonPropertyDescription(CONTENT_REPO_TARGET_DESCRIPTION)
			private String target = "";

			@JsonPropertyDescription(CONTENT_REPO_TARGET_OVERWRITE_MODE_DESCRIPTION)
			private OverwriteMode overwriteMode = OverwriteMode.INIT;

			@JsonPropertyDescription(CONTENT_REPO_CREATE_JENKINS_JOB_DESCRIPTION)
			private Boolean createJenkinsJob = false;
		}

		@Getter
		@Setter
		public static class HelmReleaseSchema {
			@JsonPropertyDescription(CONTENT_HELM_RELEASE_NAME_DESCRIPTION)
			private String name = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_REPO_URL_DESCRIPTION)
			private String repoURL = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_CHART_DESCRIPTION)
			private String chart = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_VERSION_DESCRIPTION)
			private String version = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_NAMESPACE_DESCRIPTION)
			private String namespace = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_RELEASE_NAME_DESCRIPTION)
			private String releaseName = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_VALUES_FILE_DESCRIPTION)
			private String valuesPath = "";

			@JsonPropertyDescription(CONTENT_HELM_RELEASE_VALUES_DESCRIPTION)
			private Map<String, Object> values = new HashMap<>();
		}
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class HelmConfig {
		@JsonPropertyDescription(HELM_CONFIG_CHART_DESCRIPTION)
		private String chart;

		@JsonPropertyDescription(HELM_CONFIG_REPO_URL_DESCRIPTION)
		private String repoURL;

		@JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
		private String version;
	}

	@Getter
	@Setter
	public static class HelmConfigWithValues extends HelmConfig {
		@JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
		private Map<String, Object> values = new HashMap<>();
	}

	@Getter
	@Setter
	public static class RegistrySchema {
		private Boolean internal = true;
		private Boolean twoRegistries = false;

		@Option(names = {"--registry"}, description = REGISTRY_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_ENABLE_DESCRIPTION)
		private Boolean active = false;

		@Option(names = {"--internal-registry-port"}, description = REGISTRY_INTERNAL_PORT_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_INTERNAL_PORT_DESCRIPTION)
		private Integer internalPort = DEFAULT_REGISTRY_PORT;

		@Option(names = {"--registry-url"}, description = REGISTRY_URL_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_URL_DESCRIPTION)
		private String url = "";

		@Option(names = {"--registry-path"}, description = REGISTRY_PATH_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_PATH_DESCRIPTION)
		private String path = "";

		@Option(names = {"--registry-username"}, description = REGISTRY_USERNAME_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_USERNAME_DESCRIPTION)
		private String username = "";

		@Option(names = {"--registry-password"}, description = REGISTRY_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_PASSWORD_DESCRIPTION)
		private String password = "";

		@Option(names = {"--registry-proxy-url"}, description = REGISTRY_PROXY_URL_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_PROXY_URL_DESCRIPTION)
		private String proxyUrl = "";

		@Option(names = {"--registry-proxy-path"}, description = REGISTRY_PROXY_PATH_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_PROXY_PATH_DESCRIPTION)
		private String proxyPath = "";

		@Option(names = {"--registry-proxy-username"}, description = REGISTRY_PROXY_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_PROXY_USERNAME_DESCRIPTION)
		private String proxyUsername = "";

		@Option(names = {"--registry-proxy-password"}, description = "Optional when --registry-proxy-url is set")
		@JsonPropertyDescription(REGISTRY_PROXY_PASSWORD_DESCRIPTION)
		private String proxyPassword = "";

		@Option(names = {"--registry-username-read-only"}, description = REGISTRY_USERNAME_RO_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_USERNAME_RO_DESCRIPTION)
		private String readOnlyUsername = "";

		@Option(names = {"--registry-password-read-only"}, description = REGISTRY_PASSWORD_RO_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_PASSWORD_RO_DESCRIPTION)
		private String readOnlyPassword = "";

		@Option(names = {"--create-image-pull-secrets"}, description = REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION)
		@JsonPropertyDescription(REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION)
		private Boolean createImagePullSecrets = false;

		@Option(names = {"--registry-namespace"}, description = REGISTRY_NAMESPACE)
		@JsonPropertyDescription(REGISTRY_NAMESPACE)
		private String namespace = "registry";

		@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
		private HelmConfigWithValues helm;

		public RegistrySchema() {
			helm = new HelmConfigWithValues();
			helm.setChart("docker-registry");
			helm.setRepoURL("https://twuni.github.io/docker-registry.helm");
			// renovate: depName=docker-registry registryUrl=https://twuni.github.io/docker-registry.helm
			helm.setVersion("3.0.0");
		}
	}

	@Getter
	@Setter
	public static class JenkinsSchema {
		private Boolean internal = true;
		private String urlForScm = "";
		private String ingress = "";
		private String internalBashImage = "bash:5";
		private String internalDockerClientVersion = "27.1.2";

		@Option(names = {"--jenkins"}, description = JENKINS_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_ENABLE_DESCRIPTION)
		private Boolean active = false;

		@Option(names = {"--jenkins-skip-restart"}, description = JENKINS_SKIP_RESTART_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_SKIP_RESTART_DESCRIPTION)
		private Boolean skipRestart = false;

		@Option(names = {"--jenkins-skip-plugins"}, description = JENKINS_SKIP_PLUGINS_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_SKIP_PLUGINS_DESCRIPTION)
		private Boolean skipPlugins = false;

		@Option(names = {"--jenkins-url"}, description = JENKINS_URL_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_URL_DESCRIPTION)
		private String url = "";

		@Option(names = {"--jenkins-username"}, description = JENKINS_USERNAME_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_USERNAME_DESCRIPTION)
		private String username = DEFAULT_ADMIN_USER;

		@Option(names = {"--jenkins-password"}, description = JENKINS_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_PASSWORD_DESCRIPTION)
		private String password = DEFAULT_ADMIN_PW;

		@Option(names = {"--jenkins-metrics-username"}, description = JENKINS_METRICS_USERNAME_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_METRICS_USERNAME_DESCRIPTION)
		private String metricsUsername = "metrics";

		@Option(names = {"--jenkins-metrics-password"}, description = JENKINS_METRICS_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_METRICS_PASSWORD_DESCRIPTION)
		private String metricsPassword = "metrics";

		@Option(names = {"--jenkins-image"}, description = JENKINS_IMAGE_DESCRIPTION)
		@JsonPropertyDescription(JENKINS_IMAGE_DESCRIPTION)
		private String jenkinsImage = "";

		@Option(names = {"--maven-central-mirror"}, description = MAVEN_CENTRAL_MIRROR_DESCRIPTION)
		@JsonPropertyDescription(MAVEN_CENTRAL_MIRROR_DESCRIPTION)
		private String mavenCentralMirror = "";

		@JsonPropertyDescription(OIDC_DESCPRIPTION)
		private OidcSchema oidc = new OidcSchema("jenkins");

		@Option(names = {"--jenkins-additional-envs"}, description = JENKINS_ADDITIONAL_ENVS_DESCRIPTION, split = ",", required = false)
		@JsonPropertyDescription(JENKINS_ADDITIONAL_ENVS_DESCRIPTION)
		private Map<String, String> additionalEnvs = new HashMap<>();

		@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
		private HelmConfigWithValues helm;

		@Option(names = {"--jenkins-namespace"}, description = JENKINS_NAMESPACE)
		@JsonPropertyDescription(JENKINS_NAMESPACE)
		private String namespace = "jenkins";

		public JenkinsSchema() {
			helm = new HelmConfigWithValues();
			helm.setChart("jenkins");
			helm.setRepoURL("https://charts.jenkins.io");
			// renovate: depName=jenkins registryUrl=https://charts.jenkins.io
			helm.setVersion("5.9.18");
		}
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class ApplicationSchema {
		private static final Pattern TRAILING_DASH = Pattern.compile("-$");

		private Boolean runningInsideK8s = false;
		private String namePrefixForEnvVars = "";
		private String internalKubernetesApiUrl = "";
		private String localHelmChartFolder = System.getenv("LOCAL_HELM_CHART_FOLDER");

		private NamespaceSchema namespaces = new NamespaceSchema();

		@Option(names = {"--config-file"}, description = CONFIG_FILE_DESCRIPTION, split = ",")
		private List<String> configFiles = new ArrayList<>();

		@Option(names = {"--config-map"}, description = CONFIG_MAP_DESCRIPTION, split = ",")
		private List<String> configMaps = new ArrayList<>();

		@Option(names = {"-d", "--debug"}, description = DEBUG_DESCRIPTION, scope = ScopeType.INHERIT)
		private Boolean debug = false;

		@Option(names = {"-x", "--trace"}, description = TRACE_DESCRIPTION, scope = ScopeType.INHERIT)
		private Boolean trace = false;

		@Option(names = {"--output-config-file"}, description = OUTPUT_CONFIG_FILE_DESCRIPTION, help = true)
		private Boolean outputConfigFile = false;

		@Option(names = {"-v", "--version"}, help = true, description = "Display version and license info")
		private Boolean versionInfoRequested = false;

		@Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
		private Boolean usageHelpRequested = false;

		@Option(names = {"--insecure"}, description = INSECURE_DESCRIPTION)
		@JsonPropertyDescription(INSECURE_DESCRIPTION)
		private Boolean insecure = false;

		@Option(names = {"--openshift"}, description = OPENSHIFT_DESCRIPTION)
		@JsonPropertyDescription(OPENSHIFT_DESCRIPTION)
		private Boolean openshift = false;

		@Option(names = {"--username"}, description = USERNAME_DESCRIPTION)
		@JsonPropertyDescription(USERNAME_DESCRIPTION)
		private String username = DEFAULT_ADMIN_USER;

		@Option(names = {"--password"}, description = PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(PASSWORD_DESCRIPTION)
		private String password = DEFAULT_ADMIN_PW;

		@Option(names = {"-y", "--yes"}, description = PIPE_YES_DESCRIPTION)
		@JsonPropertyDescription(PIPE_YES_DESCRIPTION)
		private Boolean yes = false;

		@Option(names = {"--name-prefix"}, description = NAME_PREFIX_DESCRIPTION)
		@JsonPropertyDescription(NAME_PREFIX_DESCRIPTION)
		private String namePrefix = "";

		@Option(names = {"--destroy"}, description = DESTROY_DESCRIPTION)
		@JsonPropertyDescription(DESTROY_DESCRIPTION)
		private Boolean destroy = false;

		@Option(names = {"--pod-resources"}, description = POD_RESOURCES_DESCRIPTION)
		@JsonPropertyDescription(POD_RESOURCES_DESCRIPTION)
		private Boolean podResources = false;

		@Option(names = {"--git-name"}, description = GIT_NAME_DESCRIPTION)
		@JsonPropertyDescription(GIT_NAME_DESCRIPTION)
		private String gitName = "Cloudogu";

		@Option(names = {"--git-email"}, description = GIT_EMAIL_DESCRIPTION)
		@JsonPropertyDescription(GIT_EMAIL_DESCRIPTION)
		private String gitEmail = "hello@cloudogu.com";

		@Option(names = {"--base-url"}, description = BASE_URL_DESCRIPTION)
		@JsonPropertyDescription(BASE_URL_DESCRIPTION)
		private String baseUrl = "";

		@Option(names = {"--url-separator-hyphen"}, description = URL_SEPARATOR_HYPHEN_DESCRIPTION)
		@JsonPropertyDescription(URL_SEPARATOR_HYPHEN_DESCRIPTION)
		private Boolean urlSeparatorHyphen = false;

		@Option(names = {"--mirror-repos"}, description = MIRROR_REPOS_DESCRIPTION)
		@JsonPropertyDescription(MIRROR_REPOS_DESCRIPTION)
		private Boolean mirrorRepos = false;

		@Option(names = {"--skip-crds"}, description = SKIP_CRDS_DESCRIPTION)
		@JsonPropertyDescription(SKIP_CRDS_DESCRIPTION)
		private Boolean skipCrds = false;

		@Option(names = {"--namespace-isolation"}, description = NAMESPACE_ISOLATION_DESCRIPTION)
		@JsonPropertyDescription(NAMESPACE_ISOLATION_DESCRIPTION)
		private Boolean namespaceIsolation = false;

		@Option(names = {"--netpols"}, description = NETPOLS_DESCRIPTION)
		@JsonPropertyDescription(NETPOLS_DESCRIPTION)
		private Boolean netpols = false;

		@Option(names = {"--cluster-admin"}, description = CLUSTER_ADMIN_DESCRIPTION)
		@JsonPropertyDescription(CLUSTER_ADMIN_DESCRIPTION)
		private Boolean clusterAdmin = false;

		@Option(names = {"-p", "--profile"}, description = APPLICATION_PROFIL)
		@JsonPropertyDescription(APPLICATION_PROFIL)
		private String profile;

		@Option(names = {"--gop-namespace"}, description = APPLICATION_GOP_NAMESPACE)
		@JsonPropertyDescription(APPLICATION_GOP_NAMESPACE)
		private String gopNamespace = "";

		@Option(names = {"-n", "--namespace"}, description = APPLICATION_NAMESPACE)
		@JsonPropertyDescription(APPLICATION_NAMESPACE)
		private String namespace = "";

		@Getter
		@Setter
		public static class NamespaceSchema {
			private LinkedHashSet<String> dedicatedNamespaces = new LinkedHashSet<>();
			private LinkedHashSet<String> tenantNamespaces = new LinkedHashSet<>();

			public Set<String> getActiveNamespaces() {
				LinkedHashSet<String> active = new LinkedHashSet<>(dedicatedNamespaces);
				active.addAll(tenantNamespaces);
				return active;
			}
		}

		@JsonIgnore
		public String getTenantName() {
			return namePrefix != null ? TRAILING_DASH.matcher(namePrefix).replaceAll("") : "";
		}
	}

	@Getter
	@Setter
	public static class FeaturesSchema {
		@Mixin
		@JsonPropertyDescription(ARGOCD_DESCRIPTION)
		private ArgoCDSchema argocd = new ArgoCDSchema();

		@Mixin
		@JsonPropertyDescription(MAIL_DESCRIPTION)
		private MailSchema mail = new MailSchema();

		@Mixin
		@JsonPropertyDescription(MONITORING_DESCRIPTION)
		private MonitoringSchema monitoring = new MonitoringSchema();

		@Mixin
		@JsonPropertyDescription(SECRETS_DESCRIPTION)
		private SecretsSchema secrets = new SecretsSchema();

		@Mixin
		@JsonPropertyDescription(INGRESS_DESCRIPTION)
		private IngressSchema ingress = new IngressSchema();

		@Mixin
		@JsonPropertyDescription(CERTMANAGER_DESCRIPTION)
		private CertManagerSchema certManager = new CertManagerSchema();
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class ArgoCDSchema {
		private Boolean configOnly = false;

		@Option(names = {"--argocd"}, description = ARGOCD_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_ENABLE_DESCRIPTION)
		private Boolean active = false;

		@Option(names = {"--argocd-operator"}, description = ARGOCD_OPERATOR_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_OPERATOR_DESCRIPTION)
		private Boolean operator = false;

		@Option(names = {"--argocd-url"}, description = ARGOCD_URL_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_URL_DESCRIPTION)
		private String url = "";

		@JsonPropertyDescription(ARGOCD_ENV_DESCRIPTION)
		private List<Map<String, String>> env;

		@Option(names = {"--argocd-email-from"}, description = ARGOCD_EMAIL_FROM_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_EMAIL_FROM_DESCRIPTION)
		private String emailFrom = "argocd@example.org";

		@Option(names = {"--argocd-email-to-user"}, description = ARGOCD_EMAIL_TO_USER_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_EMAIL_TO_USER_DESCRIPTION)
		private String emailToUser = "app-team@example.org";

		@Option(names = {"--argocd-email-to-admin"}, description = ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
		private String emailToAdmin = "infra@example.org";

		@Option(names = {"--argocd-resource-inclusions-cluster"}, description = ARGOCD_RESOURCE_INCLUSIONS_CLUSTER)
		@JsonPropertyDescription(ARGOCD_RESOURCE_INCLUSIONS_CLUSTER)
		private String resourceInclusionsCluster = "";

		@Option(names = {"--argocd-namespace"}, description = ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION)
		@JsonPropertyDescription(ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION)
		private String namespace = "argocd";

		@JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
		private Map<String, Object> values = new HashMap<>();

		@JsonPropertyDescription(OIDC_DESCPRIPTION)
		private OidcSchema oidc = new OidcSchema("argocd");
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class MailSchema {
		private Boolean active = false;

		@Option(names = {"--smtp-address"}, description = SMTP_ADDRESS_DESCRIPTION)
		@JsonPropertyDescription(SMTP_ADDRESS_DESCRIPTION)
		private String smtpAddress = "";

		@Option(names = {"--smtp-port"}, description = SMTP_PORT_DESCRIPTION)
		@JsonPropertyDescription(SMTP_PORT_DESCRIPTION)
		private Integer smtpPort;

		@Option(names = {"--smtp-user"}, description = SMTP_USER_DESCRIPTION)
		@JsonPropertyDescription(SMTP_USER_DESCRIPTION)
		private String smtpUser = "";

		@Option(names = {"--smtp-password"}, description = SMTP_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(SMTP_PASSWORD_DESCRIPTION)
		private String smtpPassword = "";
	}

	@Getter
	@Setter
	public static class MonitoringSchema {
		@Option(names = {"--metrics", "--monitoring"}, description = MONITORING_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(MONITORING_ENABLE_DESCRIPTION)
		private Boolean active = false;

		@Option(names = {"--grafana-url"}, description = GRAFANA_URL_DESCRIPTION)
		@JsonPropertyDescription(GRAFANA_URL_DESCRIPTION)
		private String grafanaUrl = "";

		@Option(names = {"--grafana-email-from"}, description = GRAFANA_EMAIL_FROM_DESCRIPTION)
		@JsonPropertyDescription(GRAFANA_EMAIL_FROM_DESCRIPTION)
		private String grafanaEmailFrom = "grafana@example.org";

		@Option(names = {"--grafana-email-to"}, description = GRAFANA_EMAIL_TO_DESCRIPTION)
		@JsonPropertyDescription(GRAFANA_EMAIL_TO_DESCRIPTION)
		private String grafanaEmailTo = "infra@example.org";

		@JsonPropertyDescription(OIDC_DESCPRIPTION)
		private OidcSchema oidc = new OidcSchema("grafana");

		@Mixin
		@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
		private MonitoringHelmSchema helm;

		@Option(names = {"--monitoring-namespace"}, description = MONITORING_NAMESPACE)
		@JsonPropertyDescription(MONITORING_NAMESPACE)
		private String namespace = "monitoring";

		public MonitoringSchema() {
			helm = new MonitoringHelmSchema();
			helm.setChart("kube-prometheus-stack");
			helm.setRepoURL("https://prometheus-community.github.io/helm-charts");
			// renovate: depName=kube-prometheus-stack registryUrl=https://prometheus-community.github.io/helm-charts
			helm.setVersion("80.2.2");
			helm.setValues(new HashMap<>());
		}

		@Getter
		@Setter
		public static class MonitoringHelmSchema extends HelmConfigWithValues {
			@Option(names = {"--grafana-image"}, description = GRAFANA_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(GRAFANA_IMAGE_DESCRIPTION)
			private String grafanaImage = "";

			@Option(names = {"--grafana-sidecar-image"}, description = GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
			private String grafanaSidecarImage = "";

			@Option(names = {"--prometheus-image"}, description = PROMETHEUS_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(PROMETHEUS_IMAGE_DESCRIPTION)
			private String prometheusImage = "";

			@Option(names = {"--prometheus-operator-image"}, description = PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
			private String prometheusOperatorImage = "";

			@Option(names = {"--prometheus-config-reloader-image"}, description = PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
			private String prometheusConfigReloaderImage = "";
		}
	}

	@Getter
	@Setter
	public static class SecretsSchema {
		private Boolean active = false;

		@Mixin
		@JsonPropertyDescription(ESO_DESCRIPTION)
		private ESOSchema externalSecrets = new ESOSchema();

		@Mixin
		@JsonPropertyDescription(VAULT_DESCRIPTION)
		private VaultSchema vault = new VaultSchema();

		@Option(names = {"--secrets-namespace"}, description = SECRETS_NAMESPACE)
		@JsonPropertyDescription(SECRETS_NAMESPACE)
		private String namespace = "secrets";

		@Getter
		@Setter
		public static class ESOSchema {
			@Mixin
			@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
			private ESOHelmSchema helm;

			public ESOSchema() {
				helm = new ESOHelmSchema();
				helm.setChart("external-secrets");
				helm.setRepoURL("https://charts.external-secrets.io");
				// renovate: depName=external-secrets registryUrl=https://charts.external-secrets.io
				helm.setVersion("0.9.16");
			}

			@Getter
			@Setter
			public static class ESOHelmSchema extends HelmConfigWithValues {
				@Option(names = {"--external-secrets-image"}, description = EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
				@JsonPropertyDescription(EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
				private String image = "";

				@Option(names = {"--external-secrets-certcontroller-image"}, description = EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
				@JsonPropertyDescription(EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
				private String certControllerImage = "";

				@Option(names = {"--external-secrets-webhook-image"}, description = EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
				@JsonPropertyDescription(EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
				private String webhookImage = "";
			}
		}

		@Getter
		@Setter
		public static class VaultSchema {
			@Option(names = {"--vault"}, description = VAULT_ENABLE_DESCRIPTION)
			@JsonPropertyDescription(VAULT_ENABLE_DESCRIPTION)
			private VaultMode mode;

			@Option(names = {"--vault-url"}, description = VAULT_URL_DESCRIPTION)
			@JsonPropertyDescription(VAULT_URL_DESCRIPTION)
			private String url = "";

			@JsonPropertyDescription(OIDC_DESCPRIPTION)
			private OidcSchema oidc = new OidcSchema("vault");

			@Mixin
			@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
			private VaultHelmSchema helm;

			public VaultSchema() {
				helm = new VaultHelmSchema();
				helm.setChart("vault");
				helm.setRepoURL("https://helm.releases.hashicorp.com");
				// renovate: depName=vault registryUrl=https://helm.releases.hashicorp.com
				helm.setVersion("0.25.0");
			}

			@Getter
			@Setter
			public static class VaultHelmSchema extends HelmConfigWithValues {
				@Option(names = {"--vault-image"}, description = VAULT_IMAGE_DESCRIPTION)
				@JsonPropertyDescription(VAULT_IMAGE_DESCRIPTION)
				private String image = "";
			}

		}
	}

	@Getter
	@Setter
	public static class OidcSchema {
		@JsonPropertyDescription("Name of the OIDC provider displayed in tool login screens")
		private String providerName = "Keycloak";

		@JsonPropertyDescription("OIDC issuer URL, for example http://keycloak.local.gd/realms/gop")
		private String issuerUrl = "";

		@JsonPropertyDescription("OIDC client ID")
		private String clientId = "";

		@JsonPropertyDescription("OIDC client secret")
		private String clientSecret = "";

		@JsonPropertyDescription("OIDC scopes requested by the tool")
		private List<String> scopes = new ArrayList<>(Arrays.asList("openid", "profile", "email"));

		@JsonPropertyDescription("OIDC group that receives full admin permissions in all OIDC-enabled tools")
		private String adminGroupName = "";

		public OidcSchema() {
		}

		private OidcSchema(String clientId) {
			this.clientId = clientId;
		}

		@JsonIgnore
		public boolean isEnabled() {
			return isNotBlank(clientSecret) && isNotBlank(issuerUrl) && isNotBlank(clientId);
		}

		private static boolean isNotBlank(String value) {
			return value != null && !value.trim().isEmpty();
		}
	}

	@Getter
	@Setter
	public static class IngressSchema {
		@Option(names = {"--ingress"}, description = INGRESS_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(INGRESS_ENABLE_DESCRIPTION)
		private Boolean active = false;

		@Mixin
		@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
		private IngressHelmSchema helm;

		@Option(names = {"--ingress-namespace"}, description = INGRESS_NAMESPACE)
		@JsonPropertyDescription(INGRESS_NAMESPACE)
		private String ingressNamespace = "ingress";

		public IngressSchema() {
			helm = new IngressHelmSchema();
			helm.setChart("traefik");
			helm.setRepoURL("https://traefik.github.io/charts");
			// renovate: depName=traefik registryUrl=https://traefik.github.io/charts
			helm.setVersion("39.0.0");
		}

		@Getter
		@Setter
		public static class IngressHelmSchema extends HelmConfigWithValues {
			@Option(names = {"--ingress-image"}, description = HELM_CONFIG_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
			private String image = "";
		}
	}

	@Getter
	@Setter
	public static class CertManagerSchema {
		@Option(names = {"--cert-manager"}, description = CERTMANAGER_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(CERTMANAGER_ENABLE_DESCRIPTION)
		private Boolean active = false;

		@Option(names = {"--cert-manager-issuer"}, description = CERTMANAGER_ENABLE_DESCRIPTION)
		@JsonPropertyDescription(CERTMANAGER_ENABLE_DESCRIPTION)
		private String issuer = "cluster-selfsigned";

		@Option(names = {"--cert-manager-namespace"}, description = CERTMANAGER_NAMESPACE)
		@JsonPropertyDescription(CERTMANAGER_NAMESPACE)
		private String namespace = "cert-manager";

		@Mixin
		@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
		private CertManagerHelmSchema helm;

		public CertManagerSchema() {
			helm = new CertManagerHelmSchema();
			helm.setChart("cert-manager");
			helm.setRepoURL("https://charts.jetstack.io");
			// renovate: depName=cert-manager registryUrl=https://charts.jetstack.io
			helm.setVersion("1.19.4");
		}

		@Getter
		@Setter
		public static class CertManagerHelmSchema extends HelmConfigWithValues {
			@Option(names = {"--cert-manager-image"}, description = CERTMANAGER_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(CERTMANAGER_IMAGE_DESCRIPTION)
			private String image = "";

			@Option(names = {"--cert-manager-webhook-image"}, description = CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION)
			private String webhookImage = "";

			@Option(names = {"--cert-manager-cainjector-image"}, description = CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION)
			private String cainjectorImage = "";

			@Option(names = {"--cert-manager-acme-solver-image"}, description = CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION)
			private String acmeSolverImage = "";

			@Option(names = {"--cert-manager-startup-api-check-image"}, description = CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION)
			@JsonPropertyDescription(CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION)
			private String startupAPICheckImage = "";
		}
	}

	public enum ContentRepoType {
		FOLDER_BASED,
		COPY,
		MIRROR
	}

	public enum VaultMode {
		dev,
		prod
	}

	public enum OverwriteMode {
		INIT,
		RESET,
		UPGRADE
	}

	public static Config fromMap(Map<String, Object> map) {
		return objectMapper.convertValue(map, Config.class);
	}

	public Map<String, Object> toMap() {
		return objectMapper.convertValue(this, new TypeReference<Map<String, Object>>() {
		});
	}

	public String toYaml(boolean includeInternals) {
		try {
			return createYamlMapper(includeInternals).writeValueAsString(this);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write Config as YAML string", e);
		}
	}

	private static YAMLMapper createYamlMapper(boolean includeInternals) {
		if (!includeInternals) {
			YAMLMapper mapper = new YAMLMapper();
			mapper.registerModule(new SimpleModule().setSerializerModifier(new BeanSerializerModifier() {
				@Override
				public List<BeanPropertyWriter> changeProperties(SerializationConfig serializationConfig,
				                                                 BeanDescription beanDesc,
				                                                 List<BeanPropertyWriter> beanProperties) {
					return beanProperties.stream()
					                     .filter(writer -> writer.getAnnotation(JsonPropertyDescription.class) != null)
					                     .toList();
				}
			}));
			return mapper;
		} else {
			return new YAMLMapper();
		}
	}
}
