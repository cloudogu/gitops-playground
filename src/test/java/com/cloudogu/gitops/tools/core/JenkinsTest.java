package com.cloudogu.gitops.tools.core;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager;
import com.cloudogu.gitops.infrastructure.jenkins.JobManager;
import com.cloudogu.gitops.infrastructure.jenkins.PrometheusConfigurator;
import com.cloudogu.gitops.infrastructure.jenkins.UserManager;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.CommandExecutorForTest;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.NetworkingUtils;
import com.cloudogu.gitops.utils.Tuple;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JenkinsTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};

	private final Config config;
	private final String expectedNodeName = "something";

	private final CommandExecutorForTest commandExecutor = new CommandExecutorForTest();
	private final GlobalPropertyManager globalPropertyManager = mock(GlobalPropertyManager.class);
	private final JobManager jobManger = mock(JobManager.class);
	private final UserManager userManager = mock(UserManager.class);
	private final PrometheusConfigurator prometheusConfigurator = mock(PrometheusConfigurator.class);
	private final Deployer deployer = mock(Deployer.class);
	private Path temporaryYamlFile;
	private final NetworkingUtils networkingUtils = mock(NetworkingUtils.class);
	private final K8sClient k8sClient = mock(K8sClient.class);
	private final ImagePullSecretCreator imagePullSecretCreator = mock(ImagePullSecretCreator.class);

	private final ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();
	private final GitHandler gitHandler = new GitHandlerForTests(scmManagerMock);

	private RepositoryWorkspace repositoryWorkspace;
	private DeploymentContext deploymentContext;
	private File localTempDir;

	JenkinsTest() {
		config = new Config();

		ScmTenantSchema scm = new ScmTenantSchema();
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setUrlForJenkins("testUrlJenkins");
		scm.setScmManager(scmManager);
		config.setScm(scm);

		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setActive(true);
		config.setJenkins(jenkins);
	}

	@BeforeEach
	void setup() {
		// waitForInternalNodeIp -> waitForNode()
		when(k8sClient.waitForNode()).thenReturn("node/" + expectedNodeName);
		when(k8sClient.run(anyString(), anyString(), anyString(), anyMap(), any(String[].class))).thenReturn("");
	}

	@Test
	void installsJenkins() throws GitAPIException, IOException {
		Jenkins jenkins = createJenkins();

		config.getJenkins().setUrl("http://jenkins");
		config.getJenkins().getHelm().setChart("jen-chart");
		config.getJenkins().getHelm().setRepoURL("https://jen-repo");
		config.getJenkins().getHelm().setVersion("4.8.1");
		config.getJenkins().setUsername("jenusr");
		config.getJenkins().setPassword("jenpw");
		config.getJenkins().setJenkinsImage("localhost:5000/proxy/jenkins-helm:custom");
		config.getJenkins().setInternalBashImage("bash:42");
		config.getJenkins().setInternalDockerClientVersion("23");

		when(k8sClient.run(anyString(), anyString(), anyString(), anyMap(), any(String[].class))).thenReturn("""
			root:x:0:
			daemon:x:1:
			docker:x:42:me
			me:x:1000:""");

		install(jenkins);

		verify(deployer).deployFeature(
			eq("https://jen-repo"),
			eq("jenkins"),
			eq("jen-chart"),
			eq("4.8.1"),
			eq("jenkins"),
			eq("jenkins"),
			eq(temporaryYamlFile),
			eq(RepoType.HELM),
			eq(true),
			eq(deploymentContext),
			eq(repositoryWorkspace)
		);

		verify(repositoryWorkspace).commitAndPushClusterResourcesChanges("Update jenkins GitOps resources");

		verify(k8sClient).label("node", expectedNodeName, new Tuple<>("node", "jenkins"));
		verify(k8sClient).labelRemove("node", "--all", "", "node");
		verify(k8sClient).createSecret(
			"generic",
			"jenkins-credentials",
			"jenkins",
			new Tuple<>("jenkins-admin-user", "jenusr"),
			new Tuple<>("jenkins-admin-password", "jenpw")
		);

		Map<String, Object> actual = parseActualYaml();
		assertThat(actual.get("dockerClientVersion").toString()).isEqualTo("23");

		Map<String, Object> controller = (Map<String, Object>) actual.get("controller");
		Map<String, Object> image = (Map<String, Object>) controller.get("image");
		assertThat(image.get("registry")).isEqualTo("localhost:5000");
		assertThat(image.get("repository")).isEqualTo("proxy/jenkins-helm");
		assertThat(image.get("tag")).isEqualTo("custom");
		assertThat(controller.get("installPlugins")).isEqualTo(false);

		assertThat(controller.get("jenkinsUrl")).isEqualTo("http://jenkins");
		assertThat(controller.get("serviceType")).isEqualTo("NodePort");

		assertThat(controller.get("ingress")).isNull();

		List<Map<String, Object>> customInitContainers = (List<Map<String, Object>>) controller.get("customInitContainers");
		assertThat(customInitContainers.get(0).get("image")).isEqualTo("bash:42");

		Map<String, Object> agent = (Map<String, Object>) actual.get("agent");
		assertThat(agent.get("runAsUser")).isEqualTo(1000);
		assertThat(agent.get("runAsGroup")).isEqualTo(42);

		ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Map> overridesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(k8sClient).run(
			nameCaptor.capture(),
			anyString(),
			eq(jenkins.getNamespace()),
			overridesCaptor.capture(),
			any(String[].class)
		);
		assertThat(nameCaptor.getValue()).startsWith("tmp-docker-gid-grepper-");

		Map<String, Object> spec = (Map<String, Object>) overridesCaptor.getValue().get("spec");
		List<Map<String, Object>> containers = (List<Map<String, Object>>) spec.get("containers");
		assertThat(containers.get(0).get("image").toString()).isEqualTo("bash:42");
	}

	@Test
	void preparesJenkinsAppContentInClusterResourcesWorkspace() throws GitAPIException {
		install(createJenkins());

		assertThat(new File(localTempDir, "apps/jenkins")).exists();
		assertThat(new File(localTempDir, "apps/jenkins/templates")).doesNotExist();
	}

	@Test
	void installsJenkinsWithoutDockerGid() throws GitAPIException, IOException {
		when(k8sClient.run(anyString(), anyString(), anyString(), anyMap(), any(String[].class))).thenReturn("""
			root:x:0:
			daemon:x:1:
			me:x:1000:""");

		install(createJenkins());

		Map<String, Object> agent = (Map<String, Object>) parseActualYaml().get("agent");
		assertThat(agent.get("runAsUser")).isEqualTo("0");
		assertThat(agent.get("runAsGroup")).isEqualTo("133");
	}

	@Test
	void installsOidcPluginBeforeJenkinsStartupWhenOidcIsConfigured() throws GitAPIException, IOException {
		config.getJenkins().setUsername("admin");
		config.getJenkins().setPassword("admin");

		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setClientId("jenkins");
		oidc.setClientSecret("jenkins-secret");
		oidc.setAdminGroupName("gop-admins");
		config.getJenkins().setOidc(oidc);

		install(createJenkins());

		Map<String, Object> controller = (Map<String, Object>) parseActualYaml().get("controller");
		List<?> installedPlugins = (List<?>) controller.get("installPlugins");
		List<String> installedPluginNames = installedPlugins.stream()
			.map(plugin -> plugin.toString().split(":")[0])
			.collect(Collectors.toList());
		assertThat(installedPluginNames).containsExactly("oic-auth", "json-path-api", "matrix-auth");

		Map<String, Object> jCasC = (Map<String, Object>) controller.get("JCasC");
		Map<String, Object> configScripts = (Map<String, Object>) jCasC.get("configScripts");
		String casc = (String) configScripts.get("oidc-auth");

		assertThat(casc).contains("clientId: \"jenkins\"");
		assertThat(casc).contains(
			"wellKnownOpenIDConfigurationUrl: \"http://keycloak.local.gd/realms/gop/.well-known/openid-configuration\""
		);
		assertThat(casc).contains("escapeHatch:");
		assertThat(casc).contains("username: \"admin\"");
		assertThat(casc).contains("group: \"gop-admins\"");
		assertThat(casc).contains("globalMatrix:");
		assertThat(casc).contains("name: \"gop-admins\"");
	}

	@Test
	void usesDefaultJenkinsOidcScopesWhenScopesAreNull() throws GitAPIException, IOException {
		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setClientId("jenkins");
		oidc.setClientSecret("jenkins-secret");
		oidc.setScopes(null);
		config.getJenkins().setOidc(oidc);

		install(createJenkins());

		Map<String, Object> controller = (Map<String, Object>) parseActualYaml().get("controller");
		Map<String, Object> jCasC = (Map<String, Object>) controller.get("JCasC");
		Map<String, Object> configScripts = (Map<String, Object>) jCasC.get("configScripts");
		String casc = (String) configScripts.get("oidc-auth");

		assertThat(casc).contains("scopesOverride: \"openid profile email\"");
	}

	@Test
	void installsOnlyIfInternal() throws GitAPIException {
		config.getJenkins().setInternal(false);
		config.getRegistry().setCreateImagePullSecrets(true);

		install(createJenkins());

		verify(deployer, never()).deployFeature(
			anyString(),
			anyString(),
			anyString(),
			anyString(),
			anyString(),
			anyString(),
			any(Path.class),
			any(),
			anyBoolean(),
			any(DeploymentContext.class),
			any(RepositoryWorkspace.class)
		);

		verify(repositoryWorkspace, never()).commitAndPushClusterResourcesChanges(anyString());

		verify(k8sClient, never()).createNamespace(any());
		verify(k8sClient, never()).createImagePullSecret(anyString(), anyString(), anyString(), anyString(), anyString());

		assertThat(temporaryYamlFile).isNull();
	}

	@Test
	void additionalHelmValuesAreMergedWithDefaultValues() throws GitAPIException, IOException {
		config.getJenkins().getHelm().setValues(Map.<String, Object>of(
			"controller",
			Map.of("nodePort", 42)
		));

		install(createJenkins());

		Map<String, Object> controller = (Map<String, Object>) parseActualYaml().get("controller");
		assertThat(controller.get("nodePort")).isEqualTo(42);
	}

	@Test
	void enablesIngressWhenBaseUrlIsSet() throws GitAPIException, IOException {
		config.getJenkins().setIngress("jenkins.localhost");
		config.getApplication().setBaseUrl("someBaseUrl");

		install(createJenkins());

		Map<String, Object> controller = (Map<String, Object>) parseActualYaml().get("controller");
		Map<String, Object> ingress = (Map<String, Object>) controller.get("ingress");
		assertThat(ingress.get("enabled")).isEqualTo(true);
		assertThat(ingress.get("hostName")).isEqualTo("jenkins.localhost");
	}

	@Test
	void mapsConfigProperly() throws GitAPIException {
		config.getApplication().setTrace(true);
		config.getFeatures().getArgocd().setActive(true);
		config.getScm().getScmManager().setUrl("http://scmm.scm-manager.svc.cluster.local/scm");
		config.getScm().getScmManager().setUsername("scmm-usr");
		config.getScm().getScmManager().setPassword("scmm-pw");
		config.getApplication().setNamePrefix("my-prefix-");
		config.getApplication().setNamePrefixForEnvVars("MY_PREFIX_");
		config.getRegistry().setUrl("reg-url");
		config.getRegistry().setPath("reg-path");
		config.getRegistry().setUsername("reg-usr");
		config.getRegistry().setPassword("reg-pw");
		config.getRegistry().setProxyUrl("reg-proxy-url");
		config.getRegistry().setProxyPath("reg-proxy-path");
		config.getRegistry().setProxyUsername("reg-proxy-usr");
		config.getRegistry().setProxyPassword("reg-proxy-pw");
		config.getJenkins().setInternal(false);
		config.getJenkins().getHelm().setVersion("4.8.1");
		config.getJenkins().setUsername("jenusr");
		config.getJenkins().setPassword("jenpw");
		config.getJenkins().setUrl("http://jenkins");
		config.getJenkins().setMetricsUsername("metrics-usr");
		config.getJenkins().setMetricsPassword("metrics-pw");
		config.getJenkins().setSkipPlugins(true);
		config.getJenkins().setSkipRestart(true);

		install(createJenkins());

		Map<String, String> env = getEnvAsMap();
		assertThat(commandExecutor.getActualCommands().get(0))
			.isEqualTo(System.getProperty("user.dir") + "/scripts/jenkins/init-jenkins.sh");

		assertThat(env.get("TRACE")).isEqualTo("true");
		assertThat(env.get("INTERNAL_JENKINS")).isEqualTo("false");
		assertThat(env.get("JENKINS_HELM_CHART_VERSION")).isEqualTo("4.8.1");
		assertThat(env.get("JENKINS_URL")).isEqualTo("http://jenkins");
		assertThat(env.get("JENKINS_USERNAME")).isEqualTo("jenusr");
		assertThat(env.get("JENKINS_PASSWORD")).isEqualTo("jenpw");
		assertThat(env.get("JENKINS_USERNAME")).isEqualTo("jenusr");
		assertThat(env.get("NAME_PREFIX")).isEqualTo("my-prefix-");
		assertThat(env.get("INSECURE")).isEqualTo("false");

		assertThat(env.get("SCM_URL")).isEqualTo("http://scmm.scm-manager.svc.cluster.local/scm");
		assertThat(env.get("SCM_PASSWORD")).isEqualTo(scmManagerMock.getCredentials().getPassword());
		assertThat(env.get("INSTALL_ARGOCD")).isEqualTo("true");

		assertThat(env.get("SKIP_PLUGINS")).isEqualTo("true");
		assertThat(env.get("SKIP_RESTART")).isEqualTo("true");

		verify(globalPropertyManager).setGlobalProperty(
			"MY_PREFIX_SCM_URL",
			"http://scmm.scm-manager.svc.cluster.local/scm"
		);
		verify(globalPropertyManager).setGlobalProperty("MY_PREFIX_K8S_VERSION", Config.K8S_VERSION);

		verify(globalPropertyManager).setGlobalProperty("MY_PREFIX_REGISTRY_URL", "reg-url");
		verify(globalPropertyManager).setGlobalProperty("MY_PREFIX_REGISTRY_PATH", "reg-path");
		verify(globalPropertyManager, never()).setGlobalProperty(eq("MY_PREFIX_REGISTRY_PROXY_URL"), anyString());
		verify(globalPropertyManager, never()).setGlobalProperty(eq("MY_PREFIX_REGISTRY_PROXY_PATH"), anyString());
		verify(globalPropertyManager, never()).setGlobalProperty(eq("MAVEN_CENTRAL_MIRROR"), anyString());

		verify(userManager).createUser("metrics-usr", "metrics-pw");
		verify(userManager).grantPermission("metrics-usr", UserManager.Permissions.METRICS_VIEW);
	}

	@Test
	void doesNotConfigurePrometheusWhenExternalJenkins() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getJenkins().setInternal(false);

		install(createJenkins());

		verify(prometheusConfigurator, never()).enableAuthentication();
	}

	@Test
	void doesNotConfigurePrometheusWhenMonitoringOff() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(false);
		config.getJenkins().setInternal(true);

		install(createJenkins());

		verify(prometheusConfigurator, never()).enableAuthentication();
	}

	@Test
	void configuresPrometheus() throws GitAPIException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getJenkins().setInternal(true);

		install(createJenkins());

		verify(prometheusConfigurator).enableAuthentication();
	}

	@Test
	void usesK8sServiceNameIfRunningAsK8sPod() throws GitAPIException {
		config.getJenkins().setInternal(true);
		config.getApplication().setRunningInsideK8s(true);

		install(createJenkins());

		assertThat(config.getJenkins().getUrl()).isEqualTo("http://jenkins.jenkins.svc.cluster.local:80");
	}

	@Test
	void usesLocalIpAndNodePortWhenOutsideOfK8s() throws GitAPIException {
		config.getJenkins().setInternal(true);
		config.getApplication().setRunningInsideK8s(false);

		when(networkingUtils.findClusterBindAddress()).thenReturn("192.168.16.2");
		when(k8sClient.waitForNodePort(anyString(), anyString())).thenReturn("42");

		install(createJenkins());

		assertThat(config.getJenkins().getUrl()).endsWith("192.168.16.2:42");
	}

	@Test
	void handlesTwoRegistries() throws GitAPIException {
		config.getRegistry().setTwoRegistries(true);
		config.getApplication().setNamePrefix("my-prefix-");
		config.getApplication().setNamePrefixForEnvVars("MY_PREFIX_");

		config.getRegistry().setUrl("reg-url");
		config.getRegistry().setPath("reg-path");
		config.getRegistry().setUsername("reg-usr");
		config.getRegistry().setPassword("reg-pw");
		config.getRegistry().setProxyUrl("reg-proxy-url");
		config.getRegistry().setProxyPath("reg-proxy-path");
		config.getRegistry().setProxyUsername("reg-proxy-usr");
		config.getRegistry().setProxyPassword("reg-proxy-pw");

		install(createJenkins());

		verify(globalPropertyManager).setGlobalProperty("MY_PREFIX_REGISTRY_PROXY_URL", "reg-proxy-url");
		verify(globalPropertyManager).setGlobalProperty("MY_PREFIX_REGISTRY_PROXY_PATH", "reg-proxy-path");

		verify(globalPropertyManager).setGlobalProperty(eq("MY_PREFIX_REGISTRY_URL"), anyString());
		verify(globalPropertyManager).setGlobalProperty(eq("MY_PREFIX_REGISTRY_PATH"), anyString());
	}

	@Test
	void doesNotCreateMetricsUserIfSecurityRealmDoesNotSupportLocalUserCreation() throws GitAPIException {
		config.getApplication().setNamePrefixForEnvVars("MY_PREFIX_");
		when(userManager.isUsingSecurityRealmWithoutLocalUserCreation()).thenReturn(true);

		install(createJenkins());

		verify(userManager, never()).createUser(anyString(), anyString());
	}

	@Test
	void globalPropertyIsSetForAdditionalEnvs() throws GitAPIException {
		config.getJenkins().setAdditionalEnvs(Map.of("ADDITIONAL_DOCKER_RUN_ARGS", "-u0:0"));

		install(createJenkins());

		verify(globalPropertyManager).setGlobalProperty(eq("ADDITIONAL_DOCKER_RUN_ARGS"), eq("-u0:0"));
	}

	@Test
	void doesNotCreateUserIfCasSecurityRealmIsUsed() throws GitAPIException {
		config.getFeatures().getArgocd().setActive(false);

		install(createJenkins());

		verify(jobManger, never()).createCredential(anyString(), anyString(), anyString(), anyString(), anyString());
		verify(jobManger, never()).startJob(anyString());
	}

	@Test
	void properlyHandlesNullValues() throws GitAPIException {
		config.getApplication().setBaseUrl(null);

		install(createJenkins());

		Map<String, String> env = getEnvAsMap();
		assertThat(env.get("BASE_URL")).isNotEqualTo("null");
	}

	@Test
	void setsMavenMirror() throws GitAPIException {
		config.getRegistry().setUrl("some value");
		config.getJenkins().setMavenCentralMirror("http://test");
		config.getApplication().setNamePrefixForEnvVars("MY_PREFIX_");

		install(createJenkins());

		verify(globalPropertyManager).setGlobalProperty(
			eq("MY_PREFIX_MAVEN_CENTRAL_MIRROR"),
			eq("http://test")
		);
	}

	protected Map<String, String> getEnvAsMap() {
		Map<String, String> env = new LinkedHashMap<>();
		for (String entry : commandExecutor.getEnvironment()) {
			String[] parts = entry.split("=");
			env.put(parts[0], parts.length > 1 ? parts[1] : null);
		}
		return env;
	}

	private Jenkins createJenkins() throws GitAPIException {
		when(networkingUtils.createUrl(anyString(), anyString(), anyString())).thenCallRealMethod();
		when(networkingUtils.createUrl(anyString(), anyString())).thenCallRealMethod();

		FileSystemUtils fileSystemUtils = new FileSystemUtils() {
			@Override
			public Path writeTempFile(Map<String, Object> mergeMap) {
				Path ret = super.writeTempFile(mergeMap);
				temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""));
				// Path after template invocation
				return ret;
			}
		};

		TestGitRepoFactory repoFactory = new TestGitRepoFactory(config, new FileSystemUtils()) {
			@Override
			public GitRepo create(String repoTarget, GitProvider scm) {
				GitRepo repo = super.create(repoTarget, scm);
				localTempDir = new File(repo.getAbsoluteLocalRepoTmpDir());
				return repo;
			}
		};

		GitRepo clusterResourcesRepo = repoFactory.create("argocd/cluster-resources", scmManagerMock);

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo));
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString());

		AirGappedUtils airGappedUtils = new AirGappedUtils(null, fileSystemUtils, null, gitHandler);

		return new Jenkins(
			commandExecutor,
			fileSystemUtils,
			globalPropertyManager,
			jobManger,
			userManager,
			prometheusConfigurator,
			deployer,
			k8sClient,
			networkingUtils,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator,
			new JenkinsToolConfigMapper(config),
			new JenkinsConfigUpdater(config)
		);
	}

	private boolean install(Jenkins jenkins) {
		deploymentContext = new ContextBuilder(config).build();
		return jenkins.execute(deploymentContext, repositoryWorkspace);
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YAML_MAPPER.readValue(temporaryYamlFile.toFile(), YAML_MAP_TYPE);
	}
}
