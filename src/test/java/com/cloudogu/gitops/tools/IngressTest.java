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
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnableKubernetesMockClient(crud = true)
class IngressTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	// setting default config values with ingress active
	private final Config config = new Config();

	private Path temporaryYamlFile;
	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();
	private File clusterResourcesRepoDir;
	private RepositoryWorkspace repositoryWorkspace;
	private DeploymentContext deploymentContext;

	private final ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();

	@Mock
	private Deployer deployer;
	@Mock
	private AirGappedUtils airGappedUtils;
	@Mock
	private GitHandler gitHandler;
	@Mock
	private GitProvider gitProvider;
	@Mock
	private ImagePullSecretCreator imagePullSecretCreator;

	KubernetesClient client;

	IngressTest() {
		config.getApplication().setNamePrefix("foo-");
		config.getFeatures().getIngress().setActive(true);
	}

	@Test
	void helmReleaseIsInstalled() throws GitAPIException, IOException {
		install(createIngress());

		/* Assert one default value */
		Map<String, Object> actual = parseActualYaml();
		Map<String, Object> deployment = (Map<String, Object>) actual.get("deployment");
		assertThat(deployment.get("replicaCount")).isEqualTo(2);

		verify(deployer).deployFeature(
			config.getFeatures().getIngress().getHelm().getRepoURL(),
			"traefik",
			config.getFeatures().getIngress().getHelm().getChart(),
			config.getFeatures().getIngress().getHelm().getVersion(),
			"foo-" + config.getFeatures().getIngress().getIngressNamespace(),
			"traefik",
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace
		);

		Map<String, Object> actualDeployment = (Map<String, Object>) parseActualYaml().get("deployment");
		assertThat(actualDeployment.get("metrics")).isNull();
		assertThat(actualDeployment.get("networkPolicy")).isNull();
		assertThat(parseActualYaml()).doesNotContainKey("imagePullSecrets");
	}

	@Test
	void preparesTraefikAppContentInClusterResourcesWorkspaceWithoutCopyingTemplates() throws GitAPIException {
		install(createIngress());

		assertThat(new File(clusterResourcesRepoDir, "apps/traefik")).exists();
		assertThat(new File(clusterResourcesRepoDir, "apps/traefik/templates")).doesNotExist();
	}

	@Test
	void setsPodResourceLimitsAndRequests() throws GitAPIException, IOException {
		config.getApplication().setPodResources(true);

		install(createIngress());

		Map<String, Object> deployment = (Map<String, Object>) parseActualYaml().get("deployment");
		assertThat((Map<String, Object>) deployment.get("resources")).containsKeys("limits", "requests");
	}

	@Test
	void whenIngressIsNotEnabledIngressHelmValuesYamlHasNoContent() throws GitAPIException {
		config.getFeatures().getIngress().setActive(false);

		assertFalse(createIngress().isEnabled(new ContextBuilder(config).build()));
	}

	@Test
	void additionalHelmValuesMergedWithDefaultValues() throws GitAPIException, IOException {
		Map<String, Object> controllerValues = new LinkedHashMap<>();
		controllerValues.put("replicaCount", 42);
		controllerValues.put("span", "7,5");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("controller", controllerValues);
		config.getFeatures().getIngress().getHelm().setValues(values);

		install(createIngress());
		Map<String, Object> actual = parseActualYaml();
		Map<String, Object> controller = (Map<String, Object>) actual.get("controller");

		assertThat(controller.get("replicaCount")).isEqualTo(42);
		assertThat(controller.get("span")).isEqualTo("7,5");
	}

	@Test
	void helmReleaseIsInstalledInAirGappedMode() throws GitAPIException, IOException {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider);
		when(gitProvider.repoUrl(any())).thenReturn("http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b");
		when(airGappedUtils.mirrorHelmRepoToGit(any(HelmChartConfig.class))).thenReturn("a/b");

		config.getApplication().setMirrorRepos(true);

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path sourceChart = rootChartsFolder.resolve("traefik");
		Files.createDirectories(sourceChart);

		Map<String, Object> chartYaml = Map.of("version", "1.2.3");
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve("Chart.yaml").toFile());

		install(createIngress());

		ArgumentCaptor<HelmChartConfig> helmConfig = ArgumentCaptor.forClass(HelmChartConfig.class);
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture());
		assertThat(helmConfig.getValue().chart()).isEqualTo("traefik");
		assertThat(helmConfig.getValue().repoURL()).isEqualTo("https://traefik.github.io/charts");
		assertThat(helmConfig.getValue().version()).isEqualTo("39.0.0");

		verify(deployer).deployFeature(
			"http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b",
			"traefik",
			".",
			"1.2.3",
			"foo-" + config.getFeatures().getIngress().getIngressNamespace(),
			"traefik",
			temporaryYamlFile,
			RepoType.GIT,
			false,
			deploymentContext,
			repositoryWorkspace
		);
	}

	@Test
	void whenMonitoringIsEnabledMetricsAreEnabled() throws GitAPIException, IOException {
		config.getFeatures().getMonitoring().setActive(true);
		config.getApplication().setNamePrefix("heliosphere");

		install(createIngress());

		Map<String, Object> actual = parseActualYaml();
		Map<String, Object> metrics = (Map<String, Object>) actual.get("metrics");
		Map<String, Object> prometheus = (Map<String, Object>) metrics.get("prometheus");
		Map<String, Object> serviceMonitor = (Map<String, Object>) prometheus.get("serviceMonitor");

		assertThat(metrics.get("enabled")).isEqualTo(true);
		assertThat(serviceMonitor.get("enabled")).isEqualTo(true);
		assertThat(serviceMonitor.get("namespace")).isEqualTo("heliospheremonitoring");
	}

	@Test
	void activatesNetworkPolicies() throws GitAPIException, IOException {
		config.getApplication().setNetpols(true);

		install(createIngress());

		Map<String, Object> actual = parseActualYaml();
		Map<String, Object> deployment = (Map<String, Object>) actual.get("deployment");
		Map<String, Object> networkPolicy = (Map<String, Object>) deployment.get("networkPolicy");

		assertThat(networkPolicy.get("enabled")).isEqualTo(true);
	}

	@Test
	void deploysImagePullSecretsForProxyRegistry() throws GitAPIException, IOException {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-pw");

		install(createIngress());

		Map<String, Object> deployment = (Map<String, Object>) parseActualYaml().get("deployment");
		assertThat(deployment.get("imagePullSecrets")).isEqualTo(List.of(Map.of("name", "proxy-registry")));
	}

	@Test
	void allowsOverridingTheImage() throws GitAPIException, IOException {
		config.getFeatures().getIngress().getHelm().setImage("localhost/abc:v42");

		install(createIngress());

		Map<String, Object> yaml = parseActualYaml();
		Map<String, Object> image = (Map<String, Object>) yaml.get("image");
		assertThat(image.get("repository")).isEqualTo("localhost/abc");
		assertThat(image.get("tag")).isEqualTo("v42");
		assertThat(image.get("digest")).isNull();
	}

	@Test
	void getNamespaceFromFeature() throws GitAPIException {
		assertThat(createIngress().getActiveNamespaceFromFeature(new ContextBuilder(config).build()))
			.isEqualTo("foo-" + config.getFeatures().getIngress().getIngressNamespace());

		config.getFeatures().getIngress().setActive(false);

		assertThat(createIngress().getActiveNamespaceFromFeature(new ContextBuilder(config).build())).isEqualTo(null);
	}

	private Ingress createIngress() throws GitAPIException {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		FileSystemUtils testFileSystemUtils = new FileSystemUtils() {
			@Override
			public Path writeTempFile(Map<String, Object> mergeMap) {
				Path ret = super.writeTempFile(mergeMap);
				temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""));
				// Path after template invocation
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

		return new Ingress(
			testFileSystemUtils,
			deployer,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator,
			new IngressToolConfigMapper(config)
		);
	}

	private boolean install(Ingress ingress) {
		deploymentContext = new ContextBuilder(config).build();
		return ingress.execute(deploymentContext, repositoryWorkspace);
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YAML_MAPPER.readValue(temporaryYamlFile.toFile(), YAML_MAP_TYPE);
	}
}
