package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.CommandExecutorForTest;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
@MockitoSettings(strictness = Strictness.LENIENT)
class VaultTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	private final Config config = new Config();

	private final CommandExecutorForTest helmCommands = new CommandExecutorForTest();
	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();
	private final Deployer deployer = mock(Deployer.class);
	private final AirGappedUtils airGappedUtils = mock(AirGappedUtils.class);

	private final ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();
	private final GitHandler gitHandler = new GitHandlerForTests(scmManagerMock);
	private final ImagePullSecretCreator imagePullSecretCreator = mock(ImagePullSecretCreator.class);

	private Path temporaryYamlFile;
	private File clusterResourcesRepoDir;
	private RepositoryWorkspace repositoryWorkspace;
	private DeploymentContext deploymentContext;

	private K8sClient k8sClient;
	KubernetesClient client;

	VaultTest() {
		config.getApplication().setNamePrefix("foo-");
		config.getFeatures().getSecrets().setActive(true);
	}

	@BeforeEach
	void init() {
		k8sClient = new K8sClient();
		k8sClient.setClient(client);
	}

	@Test
	void isDisabledViaActiveFlag() throws GitAPIException {
		config.getFeatures().getSecrets().setActive(false);

		assertFalse(createVault().isEnabled(new ContextBuilder(config).build()));
	}

	@Test
	void preparesVaultAppContentInClusterResourcesWorkspaceWithoutCopyingTemplates() throws GitAPIException {
		install(createVault());

		assertThat(new File(clusterResourcesRepoDir, "apps/vault")).exists();
		assertThat(new File(clusterResourcesRepoDir, "apps/vault/templates")).doesNotExist();
	}

	@Test
	void usesIngressIfEnabled() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setUrl("http://vault.local");

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		Map<String, Object> ingressYaml = (Map<String, Object>) server.get("ingress");
		assertThat(ingressYaml.get("enabled")).isEqualTo(true);
		List<Map<String, Object>> hosts = (List<Map<String, Object>>) ingressYaml.get("hosts");
		assertThat(hosts.get(0).get("host")).isEqualTo("vault.local");
	}

	@Test
	void usesIngressIfEnabledAndImageSet() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setUrl("http://vault.local");
		// Also set image to make sure ingress and image work at the same time under the server block
		// config.getFeatures().getSecrets().getVault().getHelm().setImage("localhost:5000/hashicorp/vault:1.12.0");

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		Map<String, Object> ingressYaml = (Map<String, Object>) server.get("ingress");
		assertThat(ingressYaml.get("enabled")).isEqualTo(true);
	}

	@Test
	void doesNotUseIngressByDefault() throws GitAPIException, IOException {
		install(createVault());

		assertThat(parseActualYaml()).doesNotContainKey("server");
	}

	@Test
	void devModeCanBeEnabledViaConfig() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setMode(Config.VaultMode.DEV);
		config.getApplication().setUsername("abc");
		config.getApplication().setPassword("123");
		config.getFeatures().getArgocd().setActive(true);

		Vault vault = createVault();

		install(vault);

		Map<String, Object> actualYaml = parseActualYaml();
		Map<String, Object> server = (Map<String, Object>) actualYaml.get("server");
		Map<String, Object> dev = (Map<String, Object>) server.get("dev");
		assertThat(dev.get("enabled")).isEqualTo(true);

		assertThat(dev.get("devRootToken")).isNotEqualTo("root");
		assertThat(dev.get("devRootToken")).isNotEqualTo(config.getApplication().getPassword());

		List<Object> actualPostStart = (List<Object>) server.get("postStart");
		assertThat(actualPostStart.get(0)).isEqualTo("/bin/sh");
		assertThat(actualPostStart.get(1)).isEqualTo("-c");

		assertThat(normalizeShellCommand((String) actualPostStart.get(2)))
			.isEqualTo("USERNAME=abc PASSWORD=123 ARGOCD=true OIDC_ENABLED=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log");

		List<Map<String, Object>> actualVolumes = (List<Map<String, Object>>) server.get("volumes");
		List<Map<String, Object>> actualVolumeMounts = (List<Map<String, Object>>) server.get("volumeMounts");
		assertThat(actualVolumes.get(0).get("name")).isEqualTo(actualVolumeMounts.get(0).get("name"));
		Map<String, Object> configMap = (Map<String, Object>) actualVolumes.get(0).get("configMap");
		assertThat(configMap.get("defaultMode")).isEqualTo(Integer.valueOf(0774));

		assertThat(actualVolumeMounts.get(0).get("readOnly")).isEqualTo(true);
		assertThat((String) actualPostStart.get(2))
			.contains((String) actualVolumeMounts.get(0).get("mountPath") + "/dev-post-start.sh");

		assertThat(server).doesNotContainKey("resources");
	}

	@Test
	void devModeCanBeEnabledViaConfigWithArgoCDDisabled() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setMode(Config.VaultMode.DEV);
		config.getApplication().setUsername("abc");
		config.getApplication().setPassword("123");

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		List<Object> actualPostStart = (List<Object>) server.get("postStart");
		assertThat(normalizeShellCommand((String) actualPostStart.get(2)))
			.isEqualTo("USERNAME=abc PASSWORD=123 ARGOCD=false OIDC_ENABLED=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log");
	}

	@Test
	void devModeEnablesOIDCOnlyWhenConfigured() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setMode(Config.VaultMode.DEV);
		config.getFeatures().getSecrets().getVault().setUrl("http://vault.localhost");
		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setClientId("vault-client");
		oidc.setClientSecret("vault-secret");
		oidc.setIssuerUrl("http://keycloak.local.gd/realms/gop");
		oidc.setAdminGroupName("gop-admins");
		config.getFeatures().getSecrets().getVault().setOidc(oidc);
		config.getApplication().setPassword("admin");

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		List<Object> actualPostStart = (List<Object>) server.get("postStart");
		assertThat(normalizeShellCommand((String) actualPostStart.get(2)))
			.isEqualTo("USERNAME=admin PASSWORD=admin ARGOCD=false OIDC_ENABLED=true OIDC_CLIENT_ID=vault-client OIDC_CLIENT_SECRET=vault-secret OIDC_DISCOVERY_URL=http://keycloak.local.gd/realms/gop OIDC_ADMIN_GROUP=gop-admins VAULT_EXTERNAL_URL=http://vault.localhost /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log");
	}

	@Test
	void devModeDoesNotEnableOIDCWhenOIDCConfigIsIncomplete() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setMode(Config.VaultMode.DEV);
		Config.OidcSchema oidc = new Config.OidcSchema();
		oidc.setClientSecret("vault-secret");
		config.getFeatures().getSecrets().getVault().setOidc(oidc);
		config.getApplication().setUsername("admin");
		config.getApplication().setPassword("admin");

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		List<Object> actualPostStart = (List<Object>) server.get("postStart");
		assertThat(normalizeShellCommand((String) actualPostStart.get(2)))
			.isEqualTo("USERNAME=admin PASSWORD=admin ARGOCD=false OIDC_ENABLED=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log");
	}

	@Test
	void prodModeCanBeEnabled() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().setMode(Config.VaultMode.PROD);

		install(createVault());

		assertThat(parseActualYaml()).doesNotContainKey("server");
	}

	@Test
	void customImageIsUsed() throws GitAPIException, IOException {
		config.getFeatures().getSecrets().getVault().getHelm().setImage("localhost:5000/hashicorp/vault:1.12.0");

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		Map<String, Object> image = (Map<String, Object>) server.get("image");
		assertThat(image.get("repository")).isEqualTo("localhost:5000/hashicorp/vault");
		assertThat(image.get("tag")).isEqualTo("1.12.0");
	}

	@Test
	void helmReleaseIsInstalled() throws GitAPIException, IOException {
		Config.SecretsSchema.VaultSchema.VaultHelmSchema helm = new Config.SecretsSchema.VaultSchema.VaultHelmSchema();
		helm.setChart("vault");
		helm.setRepoURL("https://vault-reg");
		helm.setVersion("42.23.0");
		config.getFeatures().getSecrets().getVault().setHelm(helm);

		install(createVault());

		verify(deployer).deployFeature(
			"https://vault-reg",
			"vault",
			"vault",
			"42.23.0",
			"foo-secrets",
			"vault",
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace
		);

		assertThat(parseActualYaml()).doesNotContainKey("global");
	}

	@Test
	void helmReleaseIsInstalledInAirGappedMode() throws GitAPIException, IOException {
		config.getApplication().setMirrorRepos(true);
		Config.SecretsSchema.VaultSchema.VaultHelmSchema helm = new Config.SecretsSchema.VaultSchema.VaultHelmSchema();
		helm.setChart("vault");
		helm.setRepoURL("https://vault-reg");
		helm.setVersion("42.23.0");
		config.getFeatures().getSecrets().getVault().setHelm(helm);

		when(airGappedUtils.mirrorHelmRepoToGit(any(HelmChartConfig.class))).thenReturn("a/b");

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path sourceChart = rootChartsFolder.resolve("vault");
		Files.createDirectories(sourceChart);

		Map<String, Object> chartYaml = Map.of("version", "1.2.3");
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve("Chart.yaml").toFile());

		install(createVault());

		ArgumentCaptor<HelmChartConfig> helmConfig = ArgumentCaptor.forClass(HelmChartConfig.class);
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture());
		assertThat(helmConfig.getValue().chart()).isEqualTo("vault");
		assertThat(helmConfig.getValue().repoURL()).isEqualTo("https://vault-reg");
		assertThat(helmConfig.getValue().version()).isEqualTo("42.23.0");

		verify(deployer).deployFeature(
			"http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b",
			"vault",
			".",
			"1.2.3",
			"foo-secrets",
			"vault",
			temporaryYamlFile,
			RepoType.GIT,
			false,
			deploymentContext,
			repositoryWorkspace
		);
	}

	@Test
	void setsPodResourceLimitsAndRequests() throws GitAPIException, IOException {
		config.getApplication().setPodResources(true);

		install(createVault());

		Map<String, Object> server = (Map<String, Object>) parseActualYaml().get("server");
		assertThat((Map<String, Object>) server.get("resources")).containsKeys("limits", "requests");
	}

	@Test
	void deploysImagePullSecretsForProxyRegistry() throws GitAPIException, IOException {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-pw");

		install(createVault());

		Map<String, Object> global = (Map<String, Object>) parseActualYaml().get("global");
		assertThat(global.get("imagePullSecrets")).isEqualTo(List.of(Map.of("name", "proxy-registry")));
	}

	private Vault createVault() throws GitAPIException {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		FileSystemUtils testFileSystemUtils = new FileSystemUtils() {
			@Override
			public Path writeTempFile(Map<String, Object> mapValues) {
				Path ret = super.writeTempFile(mapValues);
				temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""));
				return ret;
			}
		};

		TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, testFileSystemUtils) {
			@Override
			public GitRepo create(String repoTarget, GitProvider provider) {
				GitRepo repo = super.create(repoTarget, provider);
				clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir());

				return repo;
			}
		};

		GitRepo clusterResourcesRepo = repoProvider.create("argocd/cluster-resources", scmManagerMock);

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo));
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString());

		return new Vault(
			testFileSystemUtils,
			deployer,
			k8sClient,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator,
			new VaultToolConfigMapper(config)
		);
	}

	private boolean install(Vault vault) {
		deploymentContext = new ContextBuilder(config).build();
		return vault.execute(deploymentContext, repositoryWorkspace);
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YAML_MAPPER.readValue(temporaryYamlFile.toFile(), YAML_MAP_TYPE);
	}

	private static String normalizeShellCommand(String command) {
		return command
			.replaceAll("\\\\\\s*\\r?\\n\\s*", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}
}
