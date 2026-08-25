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
import com.cloudogu.gitops.utils.CommandExecutorForTest;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnableKubernetesMockClient(crud = true)
class ExternalSecretsOperatorTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	private final Config config = Config.fromMap(Map.of(
		"application", Map.of("namePrefix", "foo-"),
		"registry", Map.of(),
		"features", Map.of(
			"secrets", Map.of("active", true)
		)
	));

	private final CommandExecutorForTest commandExecutor = new CommandExecutorForTest();
	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();
	private Path temporaryYamlFile;
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

	@Test
	void isDisabledViaActiveFlag() throws GitAPIException {
		config.getFeatures().getSecrets().setActive(false);

		assertFalse(createExternalSecretsOperator().isEnabled(new ContextBuilder(config).build()));
	}

	@Test
	void helmReleaseIsInstalled() throws GitAPIException, IOException {
		install(createExternalSecretsOperator());

		verify(deployer).deployFeature(
			"https://charts.external-secrets.io",
			"external-secrets",
			"external-secrets",
			"0.9.16",
			"foo-secrets",
			"external-secrets",
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace
		);

		assertThat(parseActualYaml()).doesNotContainKeys("resources");
		assertThat(parseActualYaml()).doesNotContainKey("imagePullSecrets");
		assertThat(parseActualYaml()).doesNotContainKey("certController");
		assertThat(parseActualYaml()).doesNotContainKey("webhook");

		assertThat(parseActualYaml().get("installCRDs")).isNull();
	}

	@Test
	void preparesExternalSecretsAppContentInClusterResourcesWorkspaceWithoutCopyingTemplates() throws GitAPIException {
		install(createExternalSecretsOperator());

		assertThat(new File(clusterResourcesRepoDir, "apps/external-secrets")).exists();
		assertThat(new File(clusterResourcesRepoDir, "apps/external-secrets/templates")).doesNotExist();
	}

	@Test
	void skipsCrds() throws GitAPIException, IOException {
		config.getApplication().setSkipCrds(true);

		install(createExternalSecretsOperator());

		assertThat(parseActualYaml().get("installCRDs")).isEqualTo(false);
	}

	@Test
	void helmReleaseIsInstalledWithCustomImages() throws GitAPIException, IOException {
		Config.SecretsSchema.ESOSchema.ESOHelmSchema helm = new Config.SecretsSchema.ESOSchema.ESOHelmSchema();
		helm.setImage("localhost:5000/external-secrets/external-secrets:v0.6.1");
		helm.setCertControllerImage("localhost:5000/external-secrets/external-secrets-certcontroller:v0.6.1");
		helm.setWebhookImage("localhost:5000/external-secrets/external-secrets-webhook:v0.6.1");
		config.getFeatures().getSecrets().getExternalSecrets().setHelm(helm);

		install(createExternalSecretsOperator());

		Map<String, Object> valuesYaml = parseActualYaml();
		Map<String, Object> image = (Map<String, Object>) valuesYaml.get("image");
		assertThat(image.get("repository")).isEqualTo("localhost:5000/external-secrets/external-secrets");
		assertThat(image.get("tag")).isEqualTo("v0.6.1");

		Map<String, Object> certController = (Map<String, Object>) valuesYaml.get("certController");
		Map<String, Object> certControllerImage = (Map<String, Object>) certController.get("image");
		assertThat(certControllerImage.get("repository"))
			.isEqualTo("localhost:5000/external-secrets/external-secrets-certcontroller");
		assertThat(certControllerImage.get("tag")).isEqualTo("v0.6.1");

		Map<String, Object> webhook = (Map<String, Object>) valuesYaml.get("webhook");
		Map<String, Object> webhookImage = (Map<String, Object>) webhook.get("image");
		assertThat(webhookImage.get("repository"))
			.isEqualTo("localhost:5000/external-secrets/external-secrets-webhook");
		assertThat(webhookImage.get("tag")).isEqualTo("v0.6.1");
	}

	@Test
	void setsPodResourceLimitsAndRequests() throws GitAPIException, IOException {
		config.getApplication().setPodResources(true);

		install(createExternalSecretsOperator());

		assertThat((Map<String, Object>) parseActualYaml().get("resources")).containsKeys("limits", "requests");
		assertThat((Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("webhook")).get("resources"))
			.containsKeys("limits", "requests");
		assertThat((Map<String, Object>) ((Map<String, Object>) parseActualYaml().get("certController")).get("resources"))
			.containsKeys("limits", "requests");
	}

	@Test
	void helmReleaseIsInstalledInAirGappedMode() throws GitAPIException, IOException {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider);
		when(gitProvider.repoUrl(any())).thenReturn("http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b");
		when(airGappedUtils.mirrorHelmRepoToGit(any(HelmChartConfig.class))).thenReturn("a/b");

		config.getApplication().setMirrorRepos(true);

		Path rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());
		config.getApplication().setLocalHelmChartFolder(rootChartsFolder.toString());

		Path sourceChart = rootChartsFolder.resolve("external-secrets");
		Files.createDirectories(sourceChart);

		Map<String, Object> chartYaml = Map.of("version", "1.2.3");
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve("Chart.yaml").toFile());

		install(createExternalSecretsOperator());

		ArgumentCaptor<HelmChartConfig> helmConfig = ArgumentCaptor.forClass(HelmChartConfig.class);
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture());
		assertThat(helmConfig.getValue().chart()).isEqualTo("external-secrets");
		assertThat(helmConfig.getValue().repoURL()).isEqualTo("https://charts.external-secrets.io");
		assertThat(helmConfig.getValue().version()).isEqualTo("0.9.16");

		verify(deployer).deployFeature(
			eq("http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b"),
			eq("external-secrets"),
			eq("."),
			eq("1.2.3"),
			eq("foo-secrets"),
			eq("external-secrets"),
			eq(temporaryYamlFile),
			eq(RepoType.GIT),
			eq(false),
			eq(deploymentContext),
			eq(repositoryWorkspace)
		);
	}

	@Test
	void deploysImagePullSecretsForProxyRegistry() throws GitAPIException, IOException {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-pw");

		Config.SecretsSchema.ESOSchema.ESOHelmSchema helm = new Config.SecretsSchema.ESOSchema.ESOHelmSchema();
		helm.setCertControllerImage("some:thing");
		helm.setWebhookImage("some:thing");
		config.getFeatures().getSecrets().getExternalSecrets().setHelm(helm);

		install(createExternalSecretsOperator());

		List<Map<String, String>> expectedImagePullSecrets = List.of(Map.of("name", "proxy-registry"));
		assertThat(parseActualYaml().get("imagePullSecrets")).isEqualTo(expectedImagePullSecrets);
		assertThat(((Map<String, Object>) parseActualYaml().get("certController")).get("imagePullSecrets"))
			.isEqualTo(expectedImagePullSecrets);
		assertThat(((Map<String, Object>) parseActualYaml().get("webhook")).get("imagePullSecrets"))
			.isEqualTo(expectedImagePullSecrets);
	}

	private ExternalSecretsOperator createExternalSecretsOperator() throws GitAPIException {
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
				clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir());
				return repo;
			}
		};

		GitRepo clusterResourcesRepo = repoFactory.create(
			"argocd/cluster-resources",
			scmManagerMock
		);

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo));
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString());

		return new ExternalSecretsOperator(
			fileSystemUtils,
			deployer,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator,
			new ExternalSecretsOperatorToolConfigMapper(config)
		);
	}

	private boolean install(ExternalSecretsOperator operator) {
		deploymentContext = new ContextBuilder(config).build();
		return operator.execute(deploymentContext, repositoryWorkspace);
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YAML_MAPPER.readValue(temporaryYamlFile.toFile(), YAML_MAP_TYPE);
	}
}
