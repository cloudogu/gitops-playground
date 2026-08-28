package com.cloudogu.gitops.cli;

import com.cloudogu.gitops.application.content.ContentLoader;
import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryProvisioning;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.TestLogger;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.tools.common.CommonToolConfig;
import com.cloudogu.gitops.tools.core.Jenkins;
import com.cloudogu.gitops.tools.core.argocd.ArgoCD;
import com.cloudogu.gitops.tools.core.argocd.ArgoCDToolConfigMapper;
import com.cloudogu.gitops.tools.core.argocd.mode.DeploymentModeFactory;
import com.cloudogu.gitops.utils.FileSystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static uk.org.webcompere.systemstubs.SystemStubs.withEnvironmentVariable;

class ApplicationConfiguratorTest {

	static final String EXPECTED_REGISTRY_URL = "http://my-reg";
	static final int EXPECTED_REGISTRY_INTERNAL_PORT = 33333;
	static final Config.VaultMode EXPECTED_VAULT_MODE = Config.VaultMode.DEV;
	public static final String EXPECTED_JENKINS_URL = "http://my-jenkins";
	public static final String EXPECTED_SCMM_URL = "http://my-scmm";

	private ApplicationConfigurator applicationConfigurator;
	private FileSystemUtils fileSystemUtils;
	private TestLogger testLogger;
	private CommonToolConfig commonFeatureConfig;
	private ContentLoader featureContent;
	private ArgoCD featureArgoCd;
	private RepositoryProvisioning repositoryProvisioning;

	@Mock
	ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();

	Config testConfig = Config.fromMap(Map.<String, Object>of(
		"application", Map.of(
			"localHelmChartFolder", "someValue",
			"namePrefix", ""
		),
		"registry", Map.of(
			"url", EXPECTED_REGISTRY_URL,
			"proxyUrl", "proxy-" + EXPECTED_REGISTRY_URL,
			"proxyUsername", "proxy-user",
			"proxyPassword", "proxy-pw",
			"internalPort", EXPECTED_REGISTRY_INTERNAL_PORT
		),
		"jenkins", Map.of("url", EXPECTED_JENKINS_URL),
		"scm", Map.of("scmManager", Map.of("url", EXPECTED_SCMM_URL)),
		"multiTenant", Map.of("scmManager", Map.of("url", "")),
		"features", Map.of("secrets", Map.of("vault", Map.of("mode", EXPECTED_VAULT_MODE)))
	));

	@BeforeEach
	void setup() {
		fileSystemUtils = new FileSystemUtils();
		applicationConfigurator = new ApplicationConfigurator();
		testLogger = new TestLogger(applicationConfigurator.getClass());
		commonFeatureConfig = new CommonToolConfig();

		K8sClient k8sClient = Mockito.mock(K8sClient.class);
		HelmClient helmClient = Mockito.mock(HelmClient.class);
		GitRepoFactory gitRepoFactory = Mockito.mock(GitRepoFactory.class);
		Deployer deployer = Mockito.mock(Deployer.class);
		repositoryProvisioning = Mockito.mock(RepositoryProvisioning.class);

		GitHandler gitHandler = new GitHandlerForTests(scmManagerMock);
		DeploymentContext context = new ContextBuilder(testConfig).build();

		featureContent = Mockito.spy(new ContentLoader(
			testConfig,
			k8sClient,
			gitRepoFactory,
			Mockito.mock(Jenkins.class),
			gitHandler,
			fileSystemUtils,
			deployer
		));
		featureContent.isEnabled(context);

		featureArgoCd = Mockito.spy(new ArgoCD(
			k8sClient,
			helmClient,
			fileSystemUtils,
			gitHandler,
			new DeploymentModeFactory(),
			new ArgoCDToolConfigMapper(testConfig)
		));
		featureArgoCd.isEnabled(context);
	}

	@Test
	void correctConfigWithNoProgramArguments() {
		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getJenkins().getUrl()).isEqualTo(EXPECTED_JENKINS_URL);
		assertThat(actualConfig.getJenkins().getInternal()).isEqualTo(false);
		assertThat(actualConfig.getFeatures().getSecrets().getVault().getMode()).isEqualTo(EXPECTED_VAULT_MODE);

		// Dynamic value (depends on vault mode)
		assertThat(actualConfig.getFeatures().getSecrets().getActive()).isEqualTo(true);
	}

	@Test
	void setsConfigApplicationRunningInsideK8s() throws Exception {
		withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1").execute(() -> {
			Config actualConfig = applicationConfigurator.initConfig(testConfig);
			assertThat(actualConfig.getApplication().getRunningInsideK8s()).isEqualTo(true);
		});
	}

	@Test
	void setsJenkinsActiveIfExternalUrlIsSet() {
		testConfig.getJenkins().setUrl("external");
		Config actualConfig = applicationConfigurator.initConfig(testConfig);
		assertThat(actualConfig.getJenkins().getActive()).isEqualTo(true);
	}

	@Test
	void leavesJenkinsUrlForScmEmptyIfNotActive() {
		testConfig.getJenkins().setUrl("");
		testConfig.getJenkins().setActive(false);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);
		assertThat(actualConfig.getJenkins().getUrlForScm()).isEmpty();
	}

	@Test
	void failsIfMonitoringLocalIsNotSet() {
		testConfig.getApplication().setMirrorRepos(true);
		testConfig.getApplication().setLocalHelmChartFolder("");

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> commonFeatureConfig.validateConfig(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo(
			"Missing config for localHelmChartFolder.\n" +
				"Either run inside the official container image or setting env var LOCAL_HELM_CHART_FOLDER='charts' " +
				"after running 'scripts/downloadHelmCharts.sh' from the repo"
		);
	}

	@Test
	void failsIfCreateImagePullSecretsIsUsedWithoutSecrets() {
		testConfig.getRegistry().setCreateImagePullSecrets(true);

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> applicationConfigurator.initConfig(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo(
			"createImagePullSecrets needs to be used with either registry username and password or the readOnly variants"
		);
	}

	@Test
	void failsIfContentRepoIsSetWithoutMandatoryParams() {
		Config.ContentSchema.ContentRepositorySchema repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("");
		testConfig.getContent().setRepos(List.of(repo));

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> featureContent.preConfigInit(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo("content.repos requires a url parameter.");

		repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.COPY);
		repo.setTarget("missing_slash");
		testConfig.getContent().setRepos(List.of(repo));

		exception = assertThrows(RuntimeException.class, () -> featureContent.preConfigInit(testConfig));
		assertThat(exception.getMessage()).isEqualTo(
			"content.target needs / to separate namespace/group from repo name. Repo: abc"
		);
	}

	@Test
	void failsIfCopyRepoMissesTargetParameter() {
		Config.ContentSchema.ContentRepositorySchema repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.COPY);
		testConfig.getContent().setRepos(List.of(repo));

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> featureContent.preConfigInit(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo(
			"content.repos.type COPY requires content.repos.target to be set. Repo: abc"
		);
	}

	@Test
	void allowsCopyContentRepoTargetingClusterResources() {
		Config.ContentSchema.ContentRepositorySchema repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.COPY);
		repo.setTarget("argocd/cluster-resources");
		testConfig.getContent().setRepos(List.of(repo));

		Throwable exception = null;
		try {
			featureContent.preConfigInit(testConfig);
		} catch (Throwable thrown) {
			exception = thrown;
		}

		assertThat(exception).isNull();
	}

	@Test
	void failsIfFolderBasedRepoHasTargetParameter() {
		Config.ContentSchema.ContentRepositorySchema repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.FOLDER_BASED);
		repo.setTarget("namespace/repo");
		testConfig.getContent().setRepos(List.of(repo));

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> featureContent.preConfigInit(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo(
			"content.repos.type FOLDER_BASED does not support target parameter. Repo: abc"
		);

		repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.FOLDER_BASED);
		repo.setTargetRef("someRef");
		testConfig.getContent().setRepos(List.of(repo));

		exception = assertThrows(RuntimeException.class, () -> featureContent.preConfigInit(testConfig));
		assertThat(exception.getMessage()).isEqualTo(
			"content.repos.type FOLDER_BASED does not support targetRef parameter. Repo: abc"
		);
	}

	@Test
	void failsIfMirrorRepoHasInvalidConfiguration() {
		Config.ContentSchema.ContentRepositorySchema repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.MIRROR);
		testConfig.getContent().setRepos(List.of(repo));

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> featureContent.preConfigInit(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo(
			"content.repos.type MIRROR requires content.repos.target to be set. Repo: abc"
		);

		repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.MIRROR);
		repo.setTarget("namespace/repo");
		repo.setPath("non-default-path");
		testConfig.getContent().setRepos(List.of(repo));

		exception = assertThrows(RuntimeException.class, () -> featureContent.preConfigInit(testConfig));
		assertThat(exception.getMessage()).isEqualTo(
			"content.repos.type MIRROR does not support path. Current path: non-default-path. Repo: abc"
		);

		repo = new Config.ContentSchema.ContentRepositorySchema();
		repo.setUrl("abc");
		repo.setType(Config.ContentRepoType.MIRROR);
		repo.setTarget("namespace/repo");
		repo.setTemplating(true);
		testConfig.getContent().setRepos(List.of(repo));

		exception = assertThrows(RuntimeException.class, () -> featureContent.preConfigInit(testConfig));
		assertThat(exception.getMessage()).isEqualTo(
			"content.repos.type MIRROR does not support templating. Repo: abc"
		);
	}

	@Test
	void ignoresEmptyLocalHelmChartFolderIfMirrorReposIsNotSet() {
		testConfig.getApplication().setMirrorRepos(false);
		testConfig.getApplication().setLocalHelmChartFolder("");

		applicationConfigurator.initConfig(testConfig);
		// no exceptions means success
	}

	@Test
	void baseUrlEvaluatesForAllTools() {
		testConfig.getApplication().setBaseUrl("http://localhost");
		testConfig.getFeatures().getArgocd().setActive(true);
		testConfig.getFeatures().getMonitoring().setActive(true);
		testConfig.getFeatures().getSecrets().setActive(true);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getFeatures().getArgocd().getUrl()).isEqualTo("http://argocd.localhost");
		assertThat(actualConfig.getFeatures().getMonitoring().getGrafanaUrl()).isEqualTo("http://grafana.localhost");
		assertThat(actualConfig.getFeatures().getSecrets().getVault().getUrl()).isEqualTo("http://vault.localhost");
		assertThat(actualConfig.getScm().getScmManager().getIngress()).isEqualTo("scmm.localhost");
		assertThat(actualConfig.getJenkins().getIngress()).isEqualTo("jenkins.localhost");
	}

	@Test
	void baseUrlWithUrlHyphensEvaluatesForAllTools() {
		testConfig.getApplication().setBaseUrl("http://localhost");
		testConfig.getApplication().setUrlSeparatorHyphen(true);
		testConfig.getFeatures().getArgocd().setActive(true);
		testConfig.getFeatures().getMonitoring().setActive(true);
		testConfig.getFeatures().getSecrets().setActive(true);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getFeatures().getArgocd().getUrl()).isEqualTo("http://argocd-localhost");
		assertThat(actualConfig.getFeatures().getMonitoring().getGrafanaUrl()).isEqualTo("http://grafana-localhost");
		assertThat(actualConfig.getFeatures().getSecrets().getVault().getUrl()).isEqualTo("http://vault-localhost");
		assertThat(actualConfig.getScm().getScmManager().getIngress()).isEqualTo("scmm-localhost");
		assertThat(actualConfig.getJenkins().getIngress()).isEqualTo("jenkins-localhost");
	}

	@Test
	void baseUrlAlsoWorksWhenPortIsIncluded() {
		testConfig.getApplication().setBaseUrl("http://localhost:8080");
		testConfig.getFeatures().getArgocd().setActive(true);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getFeatures().getArgocd().getUrl()).isEqualTo("http://argocd.localhost:8080");
	}

	@Test
	void baseUrlAlsoWorksWhenPortIsIncludedAndUrlHyphensAreSet() {
		testConfig.getApplication().setBaseUrl("http://localhost:6502");
		testConfig.getFeatures().getArgocd().setActive(true);
		testConfig.getApplication().setUrlSeparatorHyphen(true);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getFeatures().getArgocd().getUrl()).isEqualTo("http://argocd-localhost:6502");
	}

	@Test
	void baseUrlDoesNotEvaluateForInactiveTools() {
		testConfig.getFeatures().getArgocd().setActive(false);
		testConfig.getFeatures().getMail().setActive(false);
		testConfig.getFeatures().getMonitoring().setActive(false);
		testConfig.getFeatures().getSecrets().setActive(false);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getFeatures().getArgocd().getUrl()).isEqualTo("");
		assertThat(actualConfig.getFeatures().getMonitoring().getGrafanaUrl()).isEqualTo("");
		assertThat(actualConfig.getFeatures().getSecrets().getVault().getUrl()).isEqualTo("");
	}

	@Test
	void baseUrlIndividualUrlParamsTakePrecedence() {
		testConfig.getApplication().setBaseUrl("http://localhost");
		testConfig.getFeatures().getArgocd().setActive(true);
		testConfig.getFeatures().getMail().setActive(true);
		testConfig.getFeatures().getMonitoring().setActive(true);
		testConfig.getFeatures().getSecrets().setActive(true);
		testConfig.getFeatures().getArgocd().setUrl("argocd");
		testConfig.getFeatures().getMonitoring().setGrafanaUrl("grafana");
		testConfig.getFeatures().getSecrets().getVault().setUrl("vault");

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getFeatures().getArgocd().getUrl()).isEqualTo("argocd");
		assertThat(actualConfig.getFeatures().getMonitoring().getGrafanaUrl()).isEqualTo("grafana");
		assertThat(actualConfig.getFeatures().getSecrets().getVault().getUrl()).isEqualTo("vault");
	}

	@Test
	void setsNamePrefix() {
		testConfig.getApplication().setNamePrefix("my-prefix");

		Config actualConfig = applicationConfigurator.initConfig(testConfig);
		assertThat(actualConfig.getApplication().getNamePrefix().toString()).isEqualTo("my-prefix-");
		assertThat(actualConfig.getApplication().getNamePrefixForEnvVars().toString()).isEqualTo("MY_PREFIX_");
	}

	@Test
	void setsNamePrefixWhenEndingInHyphen() {
		testConfig.getApplication().setNamePrefix("my-prefix-");

		Config actualConfig = applicationConfigurator.initConfig(testConfig);
		assertThat(actualConfig.getApplication().getNamePrefix().toString()).isEqualTo("my-prefix-");
		assertThat(actualConfig.getApplication().getNamePrefixForEnvVars().toString()).isEqualTo("MY_PREFIX_");
	}

	@Test
	void registrySetsToExternalWhenOnlyRegistryUrlSet() {
		testConfig.getRegistry().setProxyUrl(null);

		Config actualConfig = applicationConfigurator.initConfig(testConfig);

		assertThat(actualConfig.getRegistry().getInternal()).isEqualTo(false);
		assertThat(actualConfig.getRegistry().getActive()).isEqualTo(true);
	}

	@Test
	void registryFailsWhenProxyButNoUsernameAndPasswordSet() {
		String expectedException = "Proxy URL needs to be used with proxy-username and proxy-password";

		testConfig.getRegistry().setProxyUsername(null);
		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> applicationConfigurator.initConfig(testConfig)
		);
		assertThat(exception.getMessage()).isEqualTo(expectedException);

		testConfig.getRegistry().setProxyUsername("something");
		testConfig.getRegistry().setProxyPassword(null);
		exception = assertThrows(RuntimeException.class, () -> applicationConfigurator.initConfig(testConfig));
		assertThat(exception.getMessage()).isEqualTo(expectedException);

		testConfig.getRegistry().setProxyUsername(null);
		exception = assertThrows(RuntimeException.class, () -> applicationConfigurator.initConfig(testConfig));
		assertThat(exception.getMessage()).isEqualTo(expectedException);
	}

	@Test
	void validateEnvConfigAllowsValidEnvEntries() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("https://100.125.0.1:443");
		testConfig.getFeatures().getArgocd().setEnv(List.of(
			Map.of("name", "ENV_VAR_1", "value", "value1"),
			Map.of("name", "ENV_VAR_2", "value", "value2")
		));

		// No exception should be thrown
		applicationConfigurator.initConfig(testConfig);
	}

	@Test
	void validateEnvConfigThrowsExceptionForMissingNameInEnvEntry() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("https://100.125.0.1:443");
		testConfig.getFeatures().getArgocd().setEnv(List.of(
			Map.of("name", "ENV_VAR_1", "value", "value1"),
			Map.of("value", "value2")
		));

		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class, () -> {
				applicationConfigurator.initConfig(testConfig);
				featureArgoCd.postConfigInit(testConfig);
			}
		);

		assertThat(exception.getMessage()).contains(
			"Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: [value:value2]"
		);
	}

	@Test
	void validateEnvConfigThrowsExceptionForMissingValueInEnvEntry() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("https://100.125.0.1:443");
		testConfig.getFeatures().getArgocd().setEnv(List.of(
			Map.of("name", "ENV_VAR_1", "value", "value1"),
			Map.of("name", "ENV_VAR_2")
		));

		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class, () -> {
				applicationConfigurator.initConfig(testConfig);
				featureArgoCd.postConfigInit(testConfig);
			}
		);

		assertThat(exception.getMessage()).contains(
			"Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: [name:ENV_VAR_2]"
		);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void validateEnvConfigThrowsExceptionForNonMapEnvEntry() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("https://100.125.0.1:443");
		testConfig.getFeatures().getArgocd().setEnv((List) List.of(
			Map.of("name", "ENV_VAR_1", "value", "value1"),
			"invalid_entry"
		));

		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class, () -> {
				applicationConfigurator.initConfig(testConfig);
				featureArgoCd.postConfigInit(testConfig);
			}
		);

		assertThat(exception.getMessage()).contains(
			"Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: invalid_entry"
		);
	}

	@Test
	void validateEnvConfigAllowsEmptyEnvList() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("https://100.125.0.1:443");
		testConfig.getFeatures().getArgocd().getEnv();

		// No exception should be thrown
		applicationConfigurator.initConfig(testConfig);
	}

	@Test
	void validateEnvConfigSkipsValidationWhenOperatorIsFalse() {
		testConfig.getFeatures().getArgocd().setOperator(false);
		testConfig.getFeatures().getArgocd().setEnv(List.of(
			Map.of("name", "ENV_VAR_1", "value", "value1"),
			Map.of("value", "value2")
		));

		// No exception should be thrown
		applicationConfigurator.initConfig(testConfig);
	}

	@Test
	void shouldSkipResourceInclusionsClusterSetupWhenArgoCdOperatorIsNotEnabled() {
		testConfig.getFeatures().getArgocd().setOperator(false);

		// Calling the method should not make any changes to the config
		applicationConfigurator.initConfig(testConfig);

		assertThat(testLogger.getLogs().search(
			"ArgoCD operator is not enabled. Skipping features.argocd.resourceInclusionsCluster setup."
		)).isNotEmpty();
	}

	@Test
	void shouldValidateAndAcceptUserProvidedValidResourceInclusionsClusterUrl() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("https://valid-url.com");

		applicationConfigurator.initConfig(testConfig);

		assertThat(testConfig.getFeatures().getArgocd().getResourceInclusionsCluster()).isEqualTo(
			"https://valid-url.com");
		assertThat(testLogger.getLogs().search(
			"Validating user-provided features.argocd.resourceInclusionsCluster URL: https://valid-url.com"
		)).isNotEmpty();
		assertThat(testLogger.getLogs().search(
			"Found valid URL in features.argocd.resourceInclusionsCluster: https://valid-url.com"
		)).isNotEmpty();
	}

	@Test
	void shouldThrowExceptionForUserProvidedInvalidResourceInclusionsClusterUrl() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("invalid-url");

		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class,
			() -> applicationConfigurator.initConfig(testConfig)
		);

		assertThat(exception.getMessage()).contains(
			"Invalid URL for 'features.argocd.resourceInclusionsCluster': invalid-url."
		);
	}

	@Test
	void shouldSetResourceInclusionsClusterUsingKubernetesEnvVariablesWhenNotProvidedByUser() throws Exception {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster(null);

		withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1")
			.and("KUBERNETES_SERVICE_PORT", "6443")
			.execute(() -> {
				Config actualConfig = applicationConfigurator.initConfig(testConfig);

				assertThat(actualConfig.getFeatures().getArgocd().getResourceInclusionsCluster())
					.isEqualTo("https://127.0.0.1:6443");
				assertThat(testLogger.getLogs().search(
					"Successfully set features.argocd.resourceInclusionsCluster via Kubernetes ENV to: https://127.0.0.1:6443"
				)).isNotEmpty();
			});
	}

	@Test
	void multiTenantModeCentralScmUrl() {
		testConfig.getMultiTenant().setUseDedicatedInstance(true);
		testConfig.getMultiTenant().getScmManager().setUrl("scmm.localhost/scm/");
		testConfig.getApplication().setNamePrefix("foo");
		applicationConfigurator.initConfig(testConfig);
		assertThat(testConfig.getMultiTenant().getScmManager().getUrl()).isEqualTo("scmm.localhost/scm");
	}

	@Test
	void shouldThrowExceptionWhenKubernetesEnvVariablesAreNotSetAndResourceInclusionsClusterIsNull() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster(null);

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> applicationConfigurator.initConfig(testConfig)
		);

		assertThat(exception.getMessage()).contains(
			"Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. " +
				"Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly."
		);
	}

	@Test
	void shouldThrowExceptionWhenKubernetesEnvVariablesAreNotSetAndResourceInclusionsClusterIsEmpty() {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster("");

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> applicationConfigurator.initConfig(testConfig)
		);

		assertThat(exception.getMessage()).contains(
			"Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. " +
				"Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly."
		);
	}

	@Test
	void shouldThrowExceptionForInvalidKubernetesConstructedUrl() throws Exception {
		testConfig.getFeatures().getArgocd().setOperator(true);
		testConfig.getFeatures().getArgocd().setResourceInclusionsCluster(null);

		withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "invalid_host")
			.and("KUBERNETES_SERVICE_PORT", "not_a_port")
			.execute(() -> {
				RuntimeException exception = assertThrows(
					RuntimeException.class,
					() -> applicationConfigurator.initConfig(testConfig)
				);

				assertThat(exception.getMessage()).contains(
					"Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true."
				);
			});

		assertThat(testLogger.getLogs().search(
			"Constructed internal Kubernetes API Server URL: https://invalid_host:not_a_port"
		)).isNotEmpty();
	}

	@Test
	void setsAllToolNamespacesToApplicationNamespaceWhenConfigured() {
		Config config = minimalConfig();
		config.getApplication().setNamespace("platform");
		config.getApplication().setNamePrefix("tenant-a");

		config.getApplication().setGopNamespace("custom-gop");
		config.getRegistry().setNamespace("custom-registry");
		config.getJenkins().setNamespace("custom-jenkins");
		config.getScm().getScmManager().setNamespace("custom-scm");
		config.getFeatures().getArgocd().setNamespace("custom-argocd");
		config.getFeatures().getMonitoring().setNamespace("custom-monitoring");
		config.getFeatures().getSecrets().setNamespace("custom-secrets");
		config.getFeatures().getIngress().setIngressNamespace("custom-ingress");
		config.getFeatures().getCertManager().setNamespace("custom-cert-manager");
		config.getContent().setNamespaces(new ArrayList<>(List.of("old-namespace", "another-namespace")));

		Config actualConfig = applicationConfigurator.initConfig(config);

		assertThat(actualConfig.getApplication().getGopNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getRegistry().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getJenkins().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getScm().getScmManager().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getFeatures().getArgocd().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getFeatures().getMonitoring().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getFeatures().getSecrets().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getFeatures().getIngress().getIngressNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getFeatures().getCertManager().getNamespace()).isEqualTo("platform");
		assertThat(actualConfig.getContent().getNamespaces()).containsExactly("tenant-a-platform");
	}

	@Test
	void keepsIndividualToolNamespacesWhenApplicationNamespaceIsNotConfigured() {
		Config config = minimalConfig();
		config.getApplication().setNamespace("");
		config.getApplication().setNamePrefix("tenant-a");

		config.getApplication().setGopNamespace("custom-gop");
		config.getRegistry().setNamespace("custom-registry");
		config.getJenkins().setNamespace("custom-jenkins");
		config.getScm().getScmManager().setNamespace("custom-scm");
		config.getFeatures().getArgocd().setNamespace("custom-argocd");
		config.getFeatures().getMonitoring().setNamespace("custom-monitoring");
		config.getFeatures().getSecrets().setNamespace("custom-secrets");
		config.getFeatures().getIngress().setIngressNamespace("custom-ingress");
		config.getFeatures().getCertManager().setNamespace("custom-cert-manager");
		config.getContent().setNamespaces(new ArrayList<>(List.of("old-namespace", "another-namespace")));

		Config actualConfig = applicationConfigurator.initConfig(config);

		assertThat(actualConfig.getApplication().getGopNamespace()).isEqualTo("custom-gop");
		assertThat(actualConfig.getRegistry().getNamespace()).isEqualTo("custom-registry");
		assertThat(actualConfig.getJenkins().getNamespace()).isEqualTo("custom-jenkins");
		assertThat(actualConfig.getScm().getScmManager().getNamespace()).isEqualTo("custom-scm");
		assertThat(actualConfig.getFeatures().getArgocd().getNamespace()).isEqualTo("custom-argocd");
		assertThat(actualConfig.getFeatures().getMonitoring().getNamespace()).isEqualTo("custom-monitoring");
		assertThat(actualConfig.getFeatures().getSecrets().getNamespace()).isEqualTo("custom-secrets");
		assertThat(actualConfig.getFeatures().getIngress().getIngressNamespace()).isEqualTo("custom-ingress");
		assertThat(actualConfig.getFeatures().getCertManager().getNamespace()).isEqualTo("custom-cert-manager");
		assertThat(actualConfig.getContent().getNamespaces()).containsExactly("old-namespace", "another-namespace");
	}

	List<String> getAllFieldNames(Class<?> clazz) {
		return getAllFieldNames(clazz, "", new ArrayList<>());
	}

	List<String> getAllFieldNames(Class<?> clazz, String parentField, List<String> fieldNames) {
		for (Field field : clazz.getDeclaredFields()) {
			String currentField = parentField + field.getName();
			if (!field.getType().isArray() && field.getType().getName().startsWith(Config.class.getPackageName())) {
				System.out.println("nested class " + field.getType() + ", " + currentField + " + '.', " + fieldNames);
				getAllFieldNames(field.getType(), currentField + ".", fieldNames);
			} else if (!field.getName().startsWith("_") &&
				!field.getName().startsWith("$") &&
				!field.getName().equals("metaClass")) {
				fieldNames.add(currentField);
			}
		}
		return fieldNames;
	}

	List<String> getAllKeys(Map<?, ?> map) {
		return getAllKeys(map, "", new ArrayList<>());
	}

	List<String> getAllKeys(Map<?, ?> map, String parentKey, List<String> keysList) {
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String currentKey = parentKey + entry.getKey();
			Object value = entry.getValue();
			if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
				getAllKeys(nested, currentKey + ".", keysList);
			} else {
				keysList.add(currentKey);
			}
		}
		return keysList;
	}

	private static Config minimalConfig() {
		Config config = new Config();
		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setLocalHelmChartFolder("someValue");
		application.setNamePrefix("");
		config.setApplication(application);

		ScmTenantSchema scm = new ScmTenantSchema();
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setUrl("");
		scm.setScmManager(scmManager);
		config.setScm(scm);

		return config;
	}
}
