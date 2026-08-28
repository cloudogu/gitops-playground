package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy;
import com.cloudogu.gitops.infrastructure.deployment.HelmStrategy;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.PluginApi;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApi;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScmManagerSetupTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};

	private final ScmManagerProvider scmManager = mock(ScmManagerProvider.class);

	private final Deployer deployer = mock(Deployer.class);
	private final HelmStrategy helmStrategy = mock(HelmStrategy.class);

	private final GitProvider tenantProvider = mock(GitProvider.class);
	private final GitProvider centralProvider = mock(GitProvider.class);

	private final GitRepo clusterResourcesRepo = mock(GitRepo.class);
	private final GitRepo tenantBootstrapRepo = mock(GitRepo.class);

	private final ScmManagerApiClient apiClient = mock(ScmManagerApiClient.class);
	private final PluginApi pluginApi = mock(PluginApi.class);
	private final ScmManagerApi generalApi = mock(ScmManagerApi.class);
	private final FileSystemUtils fileSystemUtils = spy(new FileSystemUtils());

	private final Config config = Config.fromMap(Map.of(
		"application", Map.of(
			"namePrefix", "test",
			"insecure", true
		),
		"jenkins", Map.of(
			"active", false,
			"urlForScm", "http://jenkins.jenkins.svc.cluster.local"
		),
		"scm", Map.of(
			"scmManager", Map.ofEntries(
				Map.entry("internal", true),
				Map.entry("url", ""),
				Map.entry("namespace", "scm-manager"),
				Map.entry("username", "admin"),
				Map.entry("password", "admin"),
				Map.entry("helm", Map.of(
					"chart", "scm-manager",
					"repoURL", "https://packages.scm-manager.org/repository/helm-v2-releases/",
					"version", "3.11.2",
					"values", Map.of()
				)),
				Map.entry("urlForJenkins", "http://scmm.scm-manager.svc.cluster.local/scm"),
				Map.entry("ingress", "scmm.master.localhost"),
				Map.entry("skipRestart", false),
				Map.entry("skipPlugins", false),
				Map.entry("gitOpsUsername", "gitops"),
				Map.entry("credentials", Map.of(
					"username", "admin",
					"password", "admin"
				))
			)
		)
	));

	@BeforeEach
	void setUp() throws IOException {
		clusterResourcesRepo.setGitProvider(centralProvider);
		tenantBootstrapRepo.setGitProvider(tenantProvider);

		doReturn(centralProvider).when(clusterResourcesRepo).getGitProvider();
		doReturn(tenantProvider).when(tenantBootstrapRepo).getGitProvider();

		doReturn("argocd/cluster-resources")
			.when(clusterResourcesRepo)
			.getRepoTarget();

		doReturn("argocd/cluster-resources")
			.when(tenantBootstrapRepo)
			.getRepoTarget();

		doReturn(createTempDir("cluster-resources"))
			.when(clusterResourcesRepo)
			.getAbsoluteLocalRepoTmpDir();

		doReturn(createTempDir("tenant-bootstrap"))
			.when(tenantBootstrapRepo)
			.getAbsoluteLocalRepoTmpDir();
	}

	@Test
	@SuppressWarnings("unchecked")
	void helmChartIsInstalledCorrectly() throws IOException {
		when(scmManager.getScmmConfig()).thenReturn(config.getScm().getScmManager());
		when(deployer.getHelmStrategy()).thenReturn(helmStrategy);
		config.getScm().getScmManager().setScmmImage("localhost:5000/proxy/scm-manager:custom");
		// Usually ApplicationConfigurator modifies the namePrefix and sets it to "namePrefix-"
		config.getApplication().setNamePrefix(config.getApplication().getNamePrefix() + "-");

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo),
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		scmManagerSetup.setupHelm();
		verify(fileSystemUtils).writeTempFile(anyMap());

		ArgumentCaptor<Path> valuesPathCaptor = ArgumentCaptor.forClass(Path.class);
		verify(helmStrategy).deployFeature(eq("https://packages.scm-manager.org/repository/helm-v2-releases/"),
			eq("scm-manager"),
			eq("scm-manager"),
			eq("3.11.2"),
			eq("test-scm-manager"),
			eq("test-scmm"),
			valuesPathCaptor.capture(),
			eq(DeploymentStrategy.RepoType.HELM));

		Map<String, Object> values = YAML_MAPPER.readValue(valuesPathCaptor.getValue().toFile(), YAML_MAP_TYPE);
		Map<String, Object> image = (Map<String, Object>) values.get("image");
		assertThat(image.get("repository")).isEqualTo("localhost:5000/proxy/scm-manager");
		assertThat(image.get("tag")).isEqualTo("custom");
	}

	@Test
	@SuppressWarnings("unchecked")
	void helmValuesContainCertManagerIngressConfiguration() throws IOException {
		when(scmManager.getScmmConfig()).thenReturn(config.getScm().getScmManager());
		when(deployer.getHelmStrategy()).thenReturn(helmStrategy);
		config.getFeatures().getCertManager().setActive(true);
		config.getFeatures().getCertManager().setIssuer("cluster-selfsigned");
		// Usually ApplicationConfigurator modifies the namePrefix and sets it to "namePrefix-"
		config.getApplication().setNamePrefix(config.getApplication().getNamePrefix() + "-");

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo),
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		scmManagerSetup.setupHelm();

		ArgumentCaptor<Path> valuesPathCaptor = ArgumentCaptor.forClass(Path.class);
		verify(helmStrategy).deployFeature(eq("https://packages.scm-manager.org/repository/helm-v2-releases/"),
			eq("scm-manager"),
			eq("scm-manager"),
			eq("3.11.2"),
			eq("test-scm-manager"),
			eq("test-scmm"),
			valuesPathCaptor.capture(),
			eq(DeploymentStrategy.RepoType.HELM));

		Map<String, Object> values = YAML_MAPPER.readValue(valuesPathCaptor.getValue().toFile(), YAML_MAP_TYPE);
		Map<String, Object> ingress = (Map<String, Object>) values.get("ingress");
		List<Map<String, Object>> tls = (List<Map<String, Object>>) ingress.get("tls");
		Map<String, Object> tlsEntry = tls.get(0);
		Map<String, Object> annotations = (Map<String, Object>) ingress.get("annotations");

		assertThat(annotations.get("cert-manager.io/cluster-issuer")).isEqualTo("cluster-selfsigned");
		assertThat(tlsEntry.get("secretName")).isEqualTo("scm-manager-tls");
		assertThat((List<String>) tlsEntry.get("hosts")).containsExactly("scmm.master.localhost");
	}

	@Test
	void scmManagerPluginsAreInstalledCorrectly() throws IOException, ReflectiveOperationException {
		when(scmManager.getScmmConfig()).thenReturn(config.getScm().getScmManager());
		when(scmManager.getApiClient()).thenReturn(apiClient);

		@SuppressWarnings("unchecked")
		Call<Void> apiCall = mock(Call.class);

		when(pluginApi.install(any(String.class), anyBoolean())).thenReturn(apiCall);
		when(generalApi.checkScmmAvailable()).thenReturn(apiCall);

		when(apiClient.pluginApi()).thenReturn(pluginApi);
		when(apiClient.generalApi()).thenReturn(generalApi);

		when(apiCall.execute()).thenReturn(Response.success(null));

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo),
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		invokePrivateInstallScmmPlugins(scmManagerSetup);

		verify(pluginApi, times(10)).install(any(String.class), anyBoolean());
	}

	@Test
	void stopsWaitingWhenInterrupted() throws IOException {
		when(scmManager.getApiClient()).thenReturn(apiClient);
		when(apiClient.generalApi()).thenReturn(generalApi);

		@SuppressWarnings("unchecked")
		Call<Void> apiCall = mock(Call.class);
		@SuppressWarnings("unchecked")
		Response<Void> response = mock(Response.class);
		when(generalApi.checkScmmAvailable()).thenReturn(apiCall);
		when(apiCall.execute()).thenReturn(response);
		when(response.isSuccessful()).thenReturn(false);

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo),
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> scmManagerSetup.waitForScmmAvailable(10, 1000, 0))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Interrupted while waiting for SCM-Manager")
				.hasCauseInstanceOf(InterruptedException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void prepareBootstrapRepositoriesAfterScmManagerDeploymentInitializesClusterResourcesRepository()
		throws GitAPIException, IOException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo);

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace,
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		scmManagerSetup.prepareBootstrapRepositoriesAfterScmManagerDeployment();

		verify(centralProvider).createRepository("argocd/cluster-resources",
			"GitOps repo for basic cluster-resources",
			false);

		verify(clusterResourcesRepo).initLocalRepoIfNeeded();
		verify(clusterResourcesRepo).checkoutRemoteMainIfLocalMainMissing();
		verify(clusterResourcesRepo, never()).commitAndPush(anyString());
	}

	@Test
	void pushBootstrapRepositoriesAfterScmManagerDeploymentPushesClusterResourcesRepository()
		throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo);

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace,
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		scmManagerSetup.pushBootstrapRepositoriesAfterScmManagerDeployment();

		verify(clusterResourcesRepo).commitAndPush("Bootstrap cluster-resources repository after SCM-Manager deployment");
	}

	@Test
	void prepareBootstrapRepositoriesAfterScmManagerDeploymentInitializesBothRepositoriesInDedicatedMode()
		throws GitAPIException, IOException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo,
			tenantBootstrapRepo);

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace,
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		scmManagerSetup.prepareBootstrapRepositoriesAfterScmManagerDeployment();

		verify(centralProvider).createRepository("argocd/cluster-resources",
			"GitOps repo for basic cluster-resources",
			false);
		verify(tenantProvider).createRepository("argocd/cluster-resources",
			"GitOps repo for tenant bootstrap resources",
			false);

		verify(clusterResourcesRepo).initLocalRepoIfNeeded();
		verify(clusterResourcesRepo).checkoutRemoteMainIfLocalMainMissing();
		verify(clusterResourcesRepo, never()).commitAndPush(anyString());

		verify(tenantBootstrapRepo).initLocalRepoIfNeeded();
		verify(tenantBootstrapRepo).checkoutRemoteMainIfLocalMainMissing();
		verify(tenantBootstrapRepo, never()).commitAndPush(anyString());
	}

	@Test
	void pushBootstrapRepositoriesAfterScmManagerDeploymentPushesBothRepositoriesInDedicatedMode()
		throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo,
			tenantBootstrapRepo);

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace,
			fileSystemUtils,
			new ScmManagerToolConfigMapper(config).map(new ContextBuilder(config).build()));

		scmManagerSetup.pushBootstrapRepositoriesAfterScmManagerDeployment();

		verify(clusterResourcesRepo).commitAndPush("Bootstrap cluster-resources repository after SCM-Manager deployment");
		verify(tenantBootstrapRepo).commitAndPush("Bootstrap tenant repository after SCM-Manager deployment");
	}

	private static void invokePrivateInstallScmmPlugins(ScmManagerSetup scmManagerSetup)
		throws ReflectiveOperationException {
		Method method = ScmManagerSetup.class.getDeclaredMethod("installScmmPlugins");
		method.setAccessible(true);
		method.invoke(scmManagerSetup);
	}

	private static String createTempDir(String prefix) throws IOException {
		return Files.createTempDirectory(prefix).toFile().getCanonicalPath();
	}
}
