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
import java.util.Map;
import java.util.Objects;

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
class CertManagerTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	private final String chartVersion = "1.19.4";
	private final Config config = Config.fromMap(Map.of(
		"features", Map.of(
			"certManager", Map.of(
				"active", true,
				"helm", Map.of(
					"chart", "cert-manager",
					"repoURL", "https://charts.jetstack.io",
					"version", chartVersion
				)
			)
		)
	));

	private Path temporaryYamlFile;
	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();
	private File clusterResourcesRepoDir;
	private RepositoryWorkspace repositoryWorkspace;
	private DeploymentContext deploymentContext;

	private final ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();

	@Mock
	private Deployer deploymentStrategy;
	@Mock
	private AirGappedUtils airGappedUtils;
	@Mock
	private GitHandler gitHandler;
	@Mock
	private GitProvider gitProvider;
	@Mock
	private ImagePullSecretCreator imagePullSecretCreator;

	@Test
	void helmReleaseIsInstalled() throws GitAPIException {
		install(createCertManager());

		verify(deploymentStrategy).deployFeature(
			"https://charts.jetstack.io",
			"cert-manager",
			"cert-manager",
			chartVersion,
			"cert-manager",
			"cert-manager",
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace
		);
	}

	@Test
	void preparesCertManagerAppContentInClusterResourcesWorkspaceWithoutCopyingTemplates() throws GitAPIException {
		install(createCertManager());

		assertThat(new File(clusterResourcesRepoDir, "apps/cert-manager")).exists();
		assertThat(new File(clusterResourcesRepoDir, "apps/cert-manager/templates")).doesNotExist();
	}

	@Test
	void setsPodResourceLimitsAndRequests() throws GitAPIException, IOException {
		config.getApplication().setPodResources(true);

		install(createCertManager());

		assertThat((Map<String, Object>) parseActualYaml().get("resources")).containsKeys("limits", "requests");
		assertThat((Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("cainjector")).get("resources"))
			.containsKeys("limits", "requests");
		assertThat((Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("webhook")).get("resources"))
			.containsKeys("limits", "requests");
	}

	@Test
	void isDisabledViaActiveFlag() throws GitAPIException {
		config.getFeatures().getCertManager().setActive(false);

		assertFalse(createCertManager().isEnabled(new ContextBuilder(config).build()));
	}

	@Test
	void helmReleaseIsInstalledInAirGappedMode() throws GitAPIException, IOException {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider);
		when(gitProvider.repoUrl(any())).thenReturn("http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b");

		config.getApplication().setMirrorRepos(true);
		when(airGappedUtils.mirrorHelmRepoToGit(any(HelmChartConfig.class))).thenReturn("a/b");

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path sourceChart = rootChartsFolder.resolve("cert-manager");
		Files.createDirectories(sourceChart);

		Map<String, Object> chartYaml = Map.of("version", chartVersion);
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve("Chart.yaml").toFile());

		install(createCertManager());

		ArgumentCaptor<HelmChartConfig> helmConfig = ArgumentCaptor.forClass(HelmChartConfig.class);
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture());
		assertThat(helmConfig.getValue().chart()).isEqualTo("cert-manager");
		// check existing value, but its not used in deploy.
		assertThat(helmConfig.getValue().repoURL()).isEqualTo("https://charts.jetstack.io");
		assertThat(helmConfig.getValue().version()).isEqualTo(chartVersion);
		// important check: scmmRepoUrl is overridden with our values.
		verify(deploymentStrategy).deployFeature(
			"http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b",
			"cert-manager",
			".",
			chartVersion,
			"cert-manager",
			"cert-manager",
			temporaryYamlFile,
			RepoType.GIT,
			false,
			deploymentContext,
			repositoryWorkspace
		);
	}

	@Test
	void checkImagesAreOverriddes() throws GitAPIException, IOException {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider);
		when(gitProvider.repoUrl(any())).thenReturn("http://test");

		// Prep
		config.getApplication().setMirrorRepos(true);
		// test values
		config.getFeatures().getCertManager().getHelm()
			.setImage("this.is.my.registry:30000/this.is.my.repository/myImage:1");
		config.getFeatures().getCertManager().getHelm()
			.setWebhookImage("this.is.my.registry:30000/this.is.my.repository/myWebhook:2");
		config.getFeatures().getCertManager().getHelm()
			.setCainjectorImage("this.is.my.registry:30000/this.is.my.repository/myCainjectorImage:3");
		config.getFeatures().getCertManager().getHelm()
			.setAcmeSolverImage("this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage:4");
		config.getFeatures().getCertManager().getHelm()
			.setStartupAPICheckImage("this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage:5");

		when(airGappedUtils.mirrorHelmRepoToGit(any(HelmChartConfig.class))).thenReturn("a/b");

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path sourceChart = rootChartsFolder.resolve("cert-manager");
		Files.createDirectories(sourceChart);

		Map<String, Object> chartYaml = Map.of("version", chartVersion);
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve("Chart.yaml").toFile());

		install(createCertManager());

		// Cert-Manager
		Map<String, Object> image = (Map<String, Object>) parseActualYaml().get("image");
		assertThat(Objects.toString(image.get("repository"), null))
			.isEqualTo("this.is.my.registry:30000/this.is.my.repository/myImage");
		assertThat(Objects.toString(image.get("tag"), null)).isEqualTo("1");
		// webhook
		Map<String, Object> webhookImage = (Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("webhook")).get("image");
		assertThat(Objects.toString(webhookImage.get("repository"), null))
			.isEqualTo("this.is.my.registry:30000/this.is.my.repository/myWebhook");
		assertThat(Objects.toString(webhookImage.get("tag"), null)).isEqualTo("2");
		// cainjector
		Map<String, Object> cainjectorImage = (Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("cainjector")).get("image");
		assertThat(Objects.toString(cainjectorImage.get("repository"), null))
			.isEqualTo("this.is.my.registry:30000/this.is.my.repository/myCainjectorImage");
		assertThat(Objects.toString(cainjectorImage.get("tag"), null)).isEqualTo("3");
		// acmesolver
		Map<String, Object> acmeSolverImage = (Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("acmesolver")).get("image");
		assertThat(Objects.toString(acmeSolverImage.get("repository"), null))
			.isEqualTo("this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage");
		assertThat(Objects.toString(acmeSolverImage.get("tag"), null)).isEqualTo("4");
		// startupapicheck
		Map<String, Object> startupApiCheckImage = (Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("startupapicheck")).get("image");
		assertThat(Objects.toString(startupApiCheckImage.get("repository"), null))
			.isEqualTo("this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage");
		assertThat(Objects.toString(startupApiCheckImage.get("tag"), null)).isEqualTo("5");
	}

	private CertManager createCertManager() throws GitAPIException {
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

		GitRepo clusterResourcesRepo = repoProvider.create(
			"argocd/cluster-resources",
			scmManagerMock
		);

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo));
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString());

		return new CertManager(
			testFileSystemUtils,
			deploymentStrategy,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator,
			new CertManagerToolConfigMapper(config)
		);
	}

	private boolean install(CertManager certManager) {
		deploymentContext = new ContextBuilder(config).build();
		return certManager.execute(deploymentContext, repositoryWorkspace);
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YAML_MAPPER.readValue(temporaryYamlFile.toFile(), YAML_MAP_TYPE);
	}
}
