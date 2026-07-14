package com.cloudogu.gitops.config;

import static com.cloudogu.gitops.config.ConfigConstants.*;
import static picocli.CommandLine.ScopeType;

import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.utils.NetworkingUtils;
import jakarta.inject.Singleton;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

@Singleton
@Command(name = BINARY_NAME, description = APP_DESCRIPTION)
@SuppressWarnings({"rawtypes", "unchecked"})
public class Config {

    // When updating please also update in Dockerfile
    public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:4.2.1-1";
    // When updating please also adapt in Dockerfile, vars.tf and init-cluster.sh
    public static final String K8S_VERSION = "1.36.2";
    public static final String DEFAULT_ADMIN_USER = "admin";
    public static final String DEFAULT_ADMIN_PW = generatePassword();
    public static final int DEFAULT_REGISTRY_PORT = 30000;

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
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(sr.nextInt(62)));
        }
        return sb.toString();
    }

    public RegistrySchema getRegistry() {
        return registry;
    }

    public void setRegistry(RegistrySchema registry) {
        this.registry = registry;
    }

    public JenkinsSchema getJenkins() {
        return jenkins;
    }

    public void setJenkins(JenkinsSchema jenkins) {
        this.jenkins = jenkins;
    }

    public MultiTenantSchema getMultiTenant() {
        return multiTenant;
    }

    public void setMultiTenant(MultiTenantSchema multiTenant) {
        this.multiTenant = multiTenant;
    }

    public ScmTenantSchema getScm() {
        return scm;
    }

    public void setScm(ScmTenantSchema scm) {
        this.scm = scm;
    }

    public ApplicationSchema getApplication() {
        return application;
    }

    public void setApplication(ApplicationSchema application) {
        this.application = application;
    }

    public FeaturesSchema getFeatures() {
        return features;
    }

    public void setFeatures(FeaturesSchema features) {
        this.features = features;
    }

    public ContentSchema getContent() {
        return content;
    }

    public void setContent(ContentSchema content) {
        this.content = content;
    }

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
        private Set<String> allowedStaticsWhitelist = new HashSet<>(Arrays.asList(
                "java.lang.String",
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Double",
                "java.lang.Float",
                "java.lang.Boolean",
                "java.lang.Math",
                "com.cloudogu.gitops.utils.DockerImageParser"
        ));

        public List<String> getNamespaces() {
            return namespaces;
        }

        public void setNamespaces(List<String> namespaces) {
            this.namespaces = namespaces;
        }

        public List<ContentRepositorySchema> getRepos() {
            return repos;
        }

        public void setRepos(List<ContentRepositorySchema> repos) {
            this.repos = repos;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }

        public List<HelmReleaseSchema> getHelmReleases() {
            return helmReleases;
        }

        public void setHelmReleases(List<HelmReleaseSchema> helmReleases) {
            this.helmReleases = helmReleases;
        }

        public Boolean getUseWhitelist() {
            return useWhitelist;
        }

        public void setUseWhitelist(Boolean useWhitelist) {
            this.useWhitelist = useWhitelist;
        }

        public Set<String> getAllowedStaticsWhitelist() {
            return allowedStaticsWhitelist;
        }

        public void setAllowedStaticsWhitelist(Set<String> allowedStaticsWhitelist) {
            this.allowedStaticsWhitelist = allowedStaticsWhitelist;
        }

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

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }

            public String getRef() {
                return ref;
            }

            public void setRef(String ref) {
                this.ref = ref;
            }

            public String getTargetRef() {
                return targetRef;
            }

            public void setTargetRef(String targetRef) {
                this.targetRef = targetRef;
            }

            public Credentials getCredentials() {
                return credentials;
            }

            public void setCredentials(Credentials credentials) {
                this.credentials = credentials;
            }

            public Boolean getTemplating() {
                return templating;
            }

            public void setTemplating(Boolean templating) {
                this.templating = templating;
            }

            public ContentRepoType getType() {
                return type;
            }

            public void setType(ContentRepoType type) {
                this.type = type;
            }

            public String getTarget() {
                return target;
            }

            public void setTarget(String target) {
                this.target = target;
            }

            public OverwriteMode getOverwriteMode() {
                return overwriteMode;
            }

            public void setOverwriteMode(OverwriteMode overwriteMode) {
                this.overwriteMode = overwriteMode;
            }

            public Boolean getCreateJenkinsJob() {
                return createJenkinsJob;
            }

            public void setCreateJenkinsJob(Boolean createJenkinsJob) {
                this.createJenkinsJob = createJenkinsJob;
            }
        }

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

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getRepoURL() {
                return repoURL;
            }

            public void setRepoURL(String repoURL) {
                this.repoURL = repoURL;
            }

            public String getChart() {
                return chart;
            }

            public void setChart(String chart) {
                this.chart = chart;
            }

            public String getVersion() {
                return version;
            }

            public void setVersion(String version) {
                this.version = version;
            }

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String namespace) {
                this.namespace = namespace;
            }

            public String getReleaseName() {
                return releaseName;
            }

            public void setReleaseName(String releaseName) {
                this.releaseName = releaseName;
            }

            public String getValuesPath() {
                return valuesPath;
            }

            public void setValuesPath(String valuesPath) {
                this.valuesPath = valuesPath;
            }

            public Map<String, Object> getValues() {
                return values;
            }

            public void setValues(Map<String, Object> values) {
                this.values = values;
            }
        }
    }

    public static class HelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_CHART_DESCRIPTION)
        private String chart = null;
        @JsonPropertyDescription(HELM_CONFIG_REPO_URL_DESCRIPTION)
        private String repoURL = null;
        @JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
        private String version = null;

        public String getChart() {
            return chart;
        }

        public void setChart(String chart) {
            this.chart = chart;
        }

        public String getRepoURL() {
            return repoURL;
        }

        public void setRepoURL(String repoURL) {
            this.repoURL = repoURL;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    public static class HelmConfigWithValues extends HelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
        private Map<String, Object> values = new HashMap<>();

        public Map<String, Object> getValues() {
            return values;
        }

        public void setValues(Map<String, Object> values) {
            this.values = values;
        }
    }

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
            helm.setVersion("3.0.0");
        }

        public Boolean getInternal() {
            return internal;
        }

        public void setInternal(Boolean internal) {
            this.internal = internal;
        }

        public Boolean getTwoRegistries() {
            return twoRegistries;
        }

        public void setTwoRegistries(Boolean twoRegistries) {
            this.twoRegistries = twoRegistries;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public Integer getInternalPort() {
            return internalPort;
        }

        public void setInternalPort(Integer internalPort) {
            this.internalPort = internalPort;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getProxyUrl() {
            return proxyUrl;
        }

        public void setProxyUrl(String proxyUrl) {
            this.proxyUrl = proxyUrl;
        }

        public String getProxyPath() {
            return proxyPath;
        }

        public void setProxyPath(String proxyPath) {
            this.proxyPath = proxyPath;
        }

        public String getProxyUsername() {
            return proxyUsername;
        }

        public void setProxyUsername(String proxyUsername) {
            this.proxyUsername = proxyUsername;
        }

        public String getProxyPassword() {
            return proxyPassword;
        }

        public void setProxyPassword(String proxyPassword) {
            this.proxyPassword = proxyPassword;
        }

        public String getReadOnlyUsername() {
            return readOnlyUsername;
        }

        public void setReadOnlyUsername(String readOnlyUsername) {
            this.readOnlyUsername = readOnlyUsername;
        }

        public String getReadOnlyPassword() {
            return readOnlyPassword;
        }

        public void setReadOnlyPassword(String readOnlyPassword) {
            this.readOnlyPassword = readOnlyPassword;
        }

        public Boolean getCreateImagePullSecrets() {
            return createImagePullSecrets;
        }

        public void setCreateImagePullSecrets(Boolean createImagePullSecrets) {
            this.createImagePullSecrets = createImagePullSecrets;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public HelmConfigWithValues getHelm() {
            return helm;
        }

        public void setHelm(HelmConfigWithValues helm) {
            this.helm = helm;
        }
    }

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
        private String oidc = "";

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
            helm.setVersion("5.9.18");
        }

        public Boolean getInternal() {
            return internal;
        }

        public void setInternal(Boolean internal) {
            this.internal = internal;
        }

        public String getUrlForScm() {
            return urlForScm;
        }

        public void setUrlForScm(String urlForScm) {
            this.urlForScm = urlForScm;
        }

        public String getIngress() {
            return ingress;
        }

        public void setIngress(String ingress) {
            this.ingress = ingress;
        }

        public String getInternalBashImage() {
            return internalBashImage;
        }

        public void setInternalBashImage(String internalBashImage) {
            this.internalBashImage = internalBashImage;
        }

        public String getInternalDockerClientVersion() {
            return internalDockerClientVersion;
        }

        public void setInternalDockerClientVersion(String internalDockerClientVersion) {
            this.internalDockerClientVersion = internalDockerClientVersion;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public Boolean getSkipRestart() {
            return skipRestart;
        }

        public void setSkipRestart(Boolean skipRestart) {
            this.skipRestart = skipRestart;
        }

        public Boolean getSkipPlugins() {
            return skipPlugins;
        }

        public void setSkipPlugins(Boolean skipPlugins) {
            this.skipPlugins = skipPlugins;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getMetricsUsername() {
            return metricsUsername;
        }

        public void setMetricsUsername(String metricsUsername) {
            this.metricsUsername = metricsUsername;
        }

        public String getMetricsPassword() {
            return metricsPassword;
        }

        public void setMetricsPassword(String metricsPassword) {
            this.metricsPassword = metricsPassword;
        }

        public String getJenkinsImage() {
            return jenkinsImage;
        }

        public void setJenkinsImage(String jenkinsImage) {
            this.jenkinsImage = jenkinsImage;
        }

        public String getMavenCentralMirror() {
            return mavenCentralMirror;
        }

        public void setMavenCentralMirror(String mavenCentralMirror) {
            this.mavenCentralMirror = mavenCentralMirror;
        }

        public String getOidc() {
            return oidc;
        }

        public void setOidc(String oidc) {
            this.oidc = oidc;
        }

        public Map<String, String> getAdditionalEnvs() {
            return additionalEnvs;
        }

        public void setAdditionalEnvs(Map<String, String> additionalEnvs) {
            this.additionalEnvs = additionalEnvs;
        }

        public HelmConfigWithValues getHelm() {
            return helm;
        }

        public void setHelm(HelmConfigWithValues helm) {
            this.helm = helm;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }

    public static class ApplicationSchema {
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
        private Boolean debug;

        @Option(names = {"-x", "--trace"}, description = TRACE_DESCRIPTION, scope = ScopeType.INHERIT)
        private Boolean trace;

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

        public Boolean getRunningInsideK8s() {
            return runningInsideK8s;
        }

        public void setRunningInsideK8s(Boolean runningInsideK8s) {
            this.runningInsideK8s = runningInsideK8s;
        }

        public String getNamePrefixForEnvVars() {
            return namePrefixForEnvVars;
        }

        public void setNamePrefixForEnvVars(String namePrefixForEnvVars) {
            this.namePrefixForEnvVars = namePrefixForEnvVars;
        }

        public String getInternalKubernetesApiUrl() {
            return internalKubernetesApiUrl;
        }

        public void setInternalKubernetesApiUrl(String internalKubernetesApiUrl) {
            this.internalKubernetesApiUrl = internalKubernetesApiUrl;
        }

        public String getLocalHelmChartFolder() {
            return localHelmChartFolder;
        }

        public void setLocalHelmChartFolder(String localHelmChartFolder) {
            this.localHelmChartFolder = localHelmChartFolder;
        }

        public NamespaceSchema getNamespaces() {
            return namespaces;
        }

        public void setNamespaces(NamespaceSchema namespaces) {
            this.namespaces = namespaces;
        }

        public List<String> getConfigFiles() {
            return configFiles;
        }

        public void setConfigFiles(List<String> configFiles) {
            this.configFiles = configFiles;
        }

        public List<String> getConfigMaps() {
            return configMaps;
        }

        public void setConfigMaps(List<String> configMaps) {
            this.configMaps = configMaps;
        }

        public Boolean getDebug() {
            return debug;
        }

        public void setDebug(Boolean debug) {
            this.debug = debug;
        }

        public Boolean getTrace() {
            return trace;
        }

        public void setTrace(Boolean trace) {
            this.trace = trace;
        }

        public Boolean getOutputConfigFile() {
            return outputConfigFile;
        }

        public void setOutputConfigFile(Boolean outputConfigFile) {
            this.outputConfigFile = outputConfigFile;
        }

        public Boolean getVersionInfoRequested() {
            return versionInfoRequested;
        }

        public void setVersionInfoRequested(Boolean versionInfoRequested) {
            this.versionInfoRequested = versionInfoRequested;
        }

        public Boolean getUsageHelpRequested() {
            return usageHelpRequested;
        }

        public void setUsageHelpRequested(Boolean usageHelpRequested) {
            this.usageHelpRequested = usageHelpRequested;
        }

        public Boolean getInsecure() {
            return insecure;
        }

        public void setInsecure(Boolean insecure) {
            this.insecure = insecure;
        }

        public Boolean getOpenshift() {
            return openshift;
        }

        public void setOpenshift(Boolean openshift) {
            this.openshift = openshift;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Boolean getYes() {
            return yes;
        }

        public void setYes(Boolean yes) {
            this.yes = yes;
        }

        public String getNamePrefix() {
            return namePrefix;
        }

        public void setNamePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        public Boolean getDestroy() {
            return destroy;
        }

        public void setDestroy(Boolean destroy) {
            this.destroy = destroy;
        }

        public Boolean getPodResources() {
            return podResources;
        }

        public void setPodResources(Boolean podResources) {
            this.podResources = podResources;
        }

        public String getGitName() {
            return gitName;
        }

        public void setGitName(String gitName) {
            this.gitName = gitName;
        }

        public String getGitEmail() {
            return gitEmail;
        }

        public void setGitEmail(String gitEmail) {
            this.gitEmail = gitEmail;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Boolean getUrlSeparatorHyphen() {
            return urlSeparatorHyphen;
        }

        public void setUrlSeparatorHyphen(Boolean urlSeparatorHyphen) {
            this.urlSeparatorHyphen = urlSeparatorHyphen;
        }

        public Boolean getMirrorRepos() {
            return mirrorRepos;
        }

        public void setMirrorRepos(Boolean mirrorRepos) {
            this.mirrorRepos = mirrorRepos;
        }

        public Boolean getSkipCrds() {
            return skipCrds;
        }

        public void setSkipCrds(Boolean skipCrds) {
            this.skipCrds = skipCrds;
        }

        public Boolean getNamespaceIsolation() {
            return namespaceIsolation;
        }

        public void setNamespaceIsolation(Boolean namespaceIsolation) {
            this.namespaceIsolation = namespaceIsolation;
        }

        public Boolean getNetpols() {
            return netpols;
        }

        public void setNetpols(Boolean netpols) {
            this.netpols = netpols;
        }

        public Boolean getClusterAdmin() {
            return clusterAdmin;
        }

        public void setClusterAdmin(Boolean clusterAdmin) {
            this.clusterAdmin = clusterAdmin;
        }

        public String getProfile() {
            return profile;
        }

        public void setProfile(String profile) {
            this.profile = profile;
        }

        public String getGopNamespace() {
            return gopNamespace;
        }

        public void setGopNamespace(String gopNamespace) {
            this.gopNamespace = gopNamespace;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public static class NamespaceSchema {
            private LinkedHashSet<String> dedicatedNamespaces = new LinkedHashSet<>();
            private LinkedHashSet<String> tenantNamespaces = new LinkedHashSet<>();

            public LinkedHashSet<String> getDedicatedNamespaces() {
                return dedicatedNamespaces;
            }

            public void setDedicatedNamespaces(LinkedHashSet<String> dedicatedNamespaces) {
                this.dedicatedNamespaces = dedicatedNamespaces;
            }

            public LinkedHashSet<String> getTenantNamespaces() {
                return tenantNamespaces;
            }

            public void setTenantNamespaces(LinkedHashSet<String> tenantNamespaces) {
                this.tenantNamespaces = tenantNamespaces;
            }

            public LinkedHashSet<String> getActiveNamespaces() {
                LinkedHashSet<String> active = new LinkedHashSet<>(dedicatedNamespaces);
                active.addAll(tenantNamespaces);
                return active;
            }
        }

        @JsonIgnore
        public String getTenantName() {
            return namePrefix != null ? namePrefix.replaceAll("-$", "") : "";
        }
    }

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

        public ArgoCDSchema getArgocd() {
            return argocd;
        }

        public void setArgocd(ArgoCDSchema argocd) {
            this.argocd = argocd;
        }

        public MailSchema getMail() {
            return mail;
        }

        public void setMail(MailSchema mail) {
            this.mail = mail;
        }

        public MonitoringSchema getMonitoring() {
            return monitoring;
        }

        public void setMonitoring(MonitoringSchema monitoring) {
            this.monitoring = monitoring;
        }

        public SecretsSchema getSecrets() {
            return secrets;
        }

        public void setSecrets(SecretsSchema secrets) {
            this.secrets = secrets;
        }

        public IngressSchema getIngress() {
            return ingress;
        }

        public void setIngress(IngressSchema ingress) {
            this.ingress = ingress;
        }

        public CertManagerSchema getCertManager() {
            return certManager;
        }

        public void setCertManager(CertManagerSchema certManager) {
            this.certManager = certManager;
        }
    }

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
        private String oidc = "";

        public Boolean getConfigOnly() {
            return configOnly;
        }

        public void setConfigOnly(Boolean configOnly) {
            this.configOnly = configOnly;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public Boolean getOperator() {
            return operator;
        }

        public void setOperator(Boolean operator) {
            this.operator = operator;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public List<Map<String, String>> getEnv() {
            return env;
        }

        public void setEnv(List<Map<String, String>> env) {
            this.env = env;
        }

        public String getEmailFrom() {
            return emailFrom;
        }

        public void setEmailFrom(String emailFrom) {
            this.emailFrom = emailFrom;
        }

        public String getEmailToUser() {
            return emailToUser;
        }

        public void setEmailToUser(String emailToUser) {
            this.emailToUser = emailToUser;
        }

        public String getEmailToAdmin() {
            return emailToAdmin;
        }

        public void setEmailToAdmin(String emailToAdmin) {
            this.emailToAdmin = emailToAdmin;
        }

        public String getResourceInclusionsCluster() {
            return resourceInclusionsCluster;
        }

        public void setResourceInclusionsCluster(String resourceInclusionsCluster) {
            this.resourceInclusionsCluster = resourceInclusionsCluster;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Map<String, Object> getValues() {
            return values;
        }

        public void setValues(Map<String, Object> values) {
            this.values = values;
        }

        public String getOidc() {
            return oidc;
        }

        public void setOidc(String oidc) {
            this.oidc = oidc;
        }
    }

    public static class MailSchema {
        private Boolean active = false;

        @Option(names = {"--smtp-address"}, description = SMTP_ADDRESS_DESCRIPTION)
        @JsonPropertyDescription(SMTP_ADDRESS_DESCRIPTION)
        private String smtpAddress = "";

        @Option(names = {"--smtp-port"}, description = SMTP_PORT_DESCRIPTION)
        @JsonPropertyDescription(SMTP_PORT_DESCRIPTION)
        private Integer smtpPort = null;

        @Option(names = {"--smtp-user"}, description = SMTP_USER_DESCRIPTION)
        @JsonPropertyDescription(SMTP_USER_DESCRIPTION)
        private String smtpUser = "";

        @Option(names = {"--smtp-password"}, description = SMTP_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SMTP_PASSWORD_DESCRIPTION)
        private String smtpPassword = "";

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public String getSmtpAddress() {
            return smtpAddress;
        }

        public void setSmtpAddress(String smtpAddress) {
            this.smtpAddress = smtpAddress;
        }

        public Integer getSmtpPort() {
            return smtpPort;
        }

        public void setSmtpPort(Integer smtpPort) {
            this.smtpPort = smtpPort;
        }

        public String getSmtpUser() {
            return smtpUser;
        }

        public void setSmtpUser(String smtpUser) {
            this.smtpUser = smtpUser;
        }

        public String getSmtpPassword() {
            return smtpPassword;
        }

        public void setSmtpPassword(String smtpPassword) {
            this.smtpPassword = smtpPassword;
        }
    }

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
        private String oidc = "";

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
            helm.setVersion("80.2.2");
            helm.setValues(new HashMap<>());
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public String getGrafanaUrl() {
            return grafanaUrl;
        }

        public void setGrafanaUrl(String grafanaUrl) {
            this.grafanaUrl = grafanaUrl;
        }

        public String getGrafanaEmailFrom() {
            return grafanaEmailFrom;
        }

        public void setGrafanaEmailFrom(String grafanaEmailFrom) {
            this.grafanaEmailFrom = grafanaEmailFrom;
        }

        public String getGrafanaEmailTo() {
            return grafanaEmailTo;
        }

        public void setGrafanaEmailTo(String grafanaEmailTo) {
            this.grafanaEmailTo = grafanaEmailTo;
        }

        public String getOidc() {
            return oidc;
        }

        public void setOidc(String oidc) {
            this.oidc = oidc;
        }

        public MonitoringHelmSchema getHelm() {
            return helm;
        }

        public void setHelm(MonitoringHelmSchema helm) {
            this.helm = helm;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

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

            public String getGrafanaImage() {
                return grafanaImage;
            }

            public void setGrafanaImage(String grafanaImage) {
                this.grafanaImage = grafanaImage;
            }

            public String getGrafanaSidecarImage() {
                return grafanaSidecarImage;
            }

            public void setGrafanaSidecarImage(String grafanaSidecarImage) {
                this.grafanaSidecarImage = grafanaSidecarImage;
            }

            public String getPrometheusImage() {
                return prometheusImage;
            }

            public void setPrometheusImage(String prometheusImage) {
                this.prometheusImage = prometheusImage;
            }

            public String getPrometheusOperatorImage() {
                return prometheusOperatorImage;
            }

            public void setPrometheusOperatorImage(String prometheusOperatorImage) {
                this.prometheusOperatorImage = prometheusOperatorImage;
            }

            public String getPrometheusConfigReloaderImage() {
                return prometheusConfigReloaderImage;
            }

            public void setPrometheusConfigReloaderImage(String prometheusConfigReloaderImage) {
                this.prometheusConfigReloaderImage = prometheusConfigReloaderImage;
            }
        }
    }

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

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public ESOSchema getExternalSecrets() {
            return externalSecrets;
        }

        public void setExternalSecrets(ESOSchema externalSecrets) {
            this.externalSecrets = externalSecrets;
        }

        public VaultSchema getVault() {
            return vault;
        }

        public void setVault(VaultSchema vault) {
            this.vault = vault;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public static class ESOSchema {
            @Mixin
            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            private ESOHelmSchema helm;

            public ESOSchema() {
                helm = new ESOHelmSchema();
                helm.setChart("external-secrets");
                helm.setRepoURL("https://charts.external-secrets.io");
                helm.setVersion("0.9.16");
            }

            public ESOHelmSchema getHelm() {
                return helm;
            }

            public void setHelm(ESOHelmSchema helm) {
                this.helm = helm;
            }

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

                public String getImage() {
                    return image;
                }

                public void setImage(String image) {
                    this.image = image;
                }

                public String getCertControllerImage() {
                    return certControllerImage;
                }

                public void setCertControllerImage(String certControllerImage) {
                    this.certControllerImage = certControllerImage;
                }

                public String getWebhookImage() {
                    return webhookImage;
                }

                public void setWebhookImage(String webhookImage) {
                    this.webhookImage = webhookImage;
                }
            }
        }

        public static class VaultSchema {
            @Option(names = {"--vault"}, description = VAULT_ENABLE_DESCRIPTION)
            @JsonPropertyDescription(VAULT_ENABLE_DESCRIPTION)
            private VaultMode mode;

            @Option(names = {"--vault-url"}, description = VAULT_URL_DESCRIPTION)
            @JsonPropertyDescription(VAULT_URL_DESCRIPTION)
            private String url = "";

            @JsonPropertyDescription(OIDC_DESCPRIPTION)
            private VaultOidcSchema oidc;

            @Mixin
            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            private VaultHelmSchema helm;

            public VaultSchema() {
                helm = new VaultHelmSchema();
                helm.setChart("vault");
                helm.setRepoURL("https://helm.releases.hashicorp.com");
                helm.setVersion("0.25.0");
            }

            public VaultMode getMode() {
                return mode;
            }

            public void setMode(VaultMode mode) {
                this.mode = mode;
            }

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public VaultOidcSchema getOidc() {
                return oidc;
            }

            public void setOidc(VaultOidcSchema oidc) {
                this.oidc = oidc;
            }

            public VaultHelmSchema getHelm() {
                return helm;
            }

            public void setHelm(VaultHelmSchema helm) {
                this.helm = helm;
            }

            public static class VaultHelmSchema extends HelmConfigWithValues {
                @Option(names = {"--vault-image"}, description = VAULT_IMAGE_DESCRIPTION)
                @JsonPropertyDescription(VAULT_IMAGE_DESCRIPTION)
                private String image = "";

                public String getImage() {
                    return image;
                }

                public void setImage(String image) {
                    this.image = image;
                }
            }

            public static class VaultOidcSchema {
                @JsonPropertyDescription("OIDC client ID")
                private String clientId = "vault";
                @JsonPropertyDescription("OIDC client secret")
                private String clientSecret = "";
                @JsonPropertyDescription("OIDC discovery URL")
                private String discoveryUrl = "";

                public String getClientId() {
                    return clientId;
                }

                public void setClientId(String clientId) {
                    this.clientId = clientId;
                }

                public String getClientSecret() {
                    return clientSecret;
                }

                public void setClientSecret(String clientSecret) {
                    this.clientSecret = clientSecret;
                }

                public String getDiscoveryUrl() {
                    return discoveryUrl;
                }

                public void setDiscoveryUrl(String discoveryUrl) {
                    this.discoveryUrl = discoveryUrl;
                }
            }
        }
    }

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
            helm.setVersion("39.0.0");
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public IngressHelmSchema getHelm() {
            return helm;
        }

        public void setHelm(IngressHelmSchema helm) {
            this.helm = helm;
        }

        public String getIngressNamespace() {
            return ingressNamespace;
        }

        public void setIngressNamespace(String ingressNamespace) {
            this.ingressNamespace = ingressNamespace;
        }

        public static class IngressHelmSchema extends HelmConfigWithValues {
            @Option(names = {"--ingress-image"}, description = HELM_CONFIG_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
            private String image = "";

            public String getImage() {
                return image;
            }

            public void setImage(String image) {
                this.image = image;
            }
        }
    }

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
            helm.setVersion("1.19.4");
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public CertManagerHelmSchema getHelm() {
            return helm;
        }

        public void setHelm(CertManagerHelmSchema helm) {
            this.helm = helm;
        }

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

            public String getImage() {
                return image;
            }

            public void setImage(String image) {
                this.image = image;
            }

            public String getWebhookImage() {
                return webhookImage;
            }

            public void setWebhookImage(String webhookImage) {
                this.webhookImage = webhookImage;
            }

            public String getCainjectorImage() {
                return cainjectorImage;
            }

            public void setCainjectorImage(String cainjectorImage) {
                this.cainjectorImage = cainjectorImage;
            }

            public String getAcmeSolverImage() {
                return acmeSolverImage;
            }

            public void setAcmeSolverImage(String acmeSolverImage) {
                this.acmeSolverImage = acmeSolverImage;
            }

            public String getStartupAPICheckImage() {
                return startupAPICheckImage;
            }

            public void setStartupAPICheckImage(String startupAPICheckImage) {
                this.startupAPICheckImage = startupAPICheckImage;
            }
        }
    }

    public enum ContentRepoType {
        FOLDER_BASED, COPY, MIRROR
    }

    public enum VaultMode {
        dev, prod
    }

    public enum OverwriteMode {
        INIT, RESET, UPGRADE
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(groovy.lang.GString.class, new JsonSerializer<groovy.lang.GString>() {
                @Override
                public void serialize(groovy.lang.GString value, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
                    jsonGenerator.writeString(value.toString());
                }
            }));

    public static Config fromMap(Map map) {
        return objectMapper.convertValue(map, Config.class);
    }

    public Map toMap() {
        return objectMapper.convertValue(this, Map.class);
    }

    public String toYaml(boolean includeInternals) {
        try {
            return createYamlMapper(includeInternals).writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Config as YAML string", e);
        }
    }

    private static YAMLMapper createYamlMapper(boolean includeInternals) {
        if (!includeInternals) {
            YAMLMapper mapper = new YAMLMapper();
            mapper.registerModule(new SimpleModule().setSerializerModifier(new BeanSerializerModifier() {
                @Override
                public List<BeanPropertyWriter> changeProperties(SerializationConfig serializationConfig, BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
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
