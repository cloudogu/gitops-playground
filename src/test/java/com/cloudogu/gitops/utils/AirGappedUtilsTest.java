package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.Permission;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.Repository;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.testhelper.git.TestScmManagerApiClient;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AirGappedUtilsTest {

	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

	private Path rootChartsFolder;
	private Config config;
	private HelmChartConfig helmConfig;
	private TestGitRepoFactory gitRepoFactory;
	private FileSystemUtils fileSystemUtils;
	private TestScmManagerApiClient scmmApiClient;
	private HelmClient helmClient;
	private GitHandler gitHandler;

	@BeforeEach
	void setUp() throws IOException {
		rootChartsFolder = Files.createTempDirectory(getClass().getSimpleName());

		Map<String, Object> configMap = new LinkedHashMap<>();
		configMap.put("application", Map.of(
			"gitName", "Cloudogu",
			"gitEmail", "hello@cloudogu.com"
		));
		configMap.put("scm", Map.of(
			"scmManager", Map.of("url", "")
		));
		config = Config.fromMap(configMap);

		helmConfig = HelmChartConfig.builder()
			.chart("kube-prometheus-stack")
			.repoURL("https://kube-prometheus-stack-repo-url")
			.version("58.2.1")
			.localHelmChartFolder(rootChartsFolder.toString())
			.build();

		fileSystemUtils = new FileSystemUtils();
		gitRepoFactory = new TestGitRepoFactory(config, fileSystemUtils);
		scmmApiClient = new TestScmManagerApiClient(config);
		helmClient = mock(HelmClient.class);
		gitHandler = new GitHandlerForTests(new ScmManagerProviderMock());

		var response = TestScmManagerApiClient.mockSuccessfulResponse(201);
		when(scmmApiClient.getRepositoryApi().create(any(Repository.class), anyBoolean())).thenReturn(response);
		when(scmmApiClient.getRepositoryApi().createPermission(anyString(), anyString(), any(Permission.class)))
			.thenReturn(response);
	}

	@Test
	void preparesReposForAirGappedUse() throws IOException, GitAPIException {
		setupForAirgappedUse();

		String actualRepoNamespaceAndName = createAirGappedUtils().mirrorHelmRepoToGit(helmConfig);

		assertThat(actualRepoNamespaceAndName)
			.isEqualTo(GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES + "/kube-prometheus-stack");
		assertAirGapped();
		verify(helmClient).template("kube-prometheus-stack", rootChartsFolder + "/kube-prometheus-stack");
	}

	@Test
	void failsWhenUnableToResolveVersionOfDependencies() throws IOException {
		setupForAirgappedUse(Collections.emptyMap());

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
		);

		assertThat(exception.getMessage()).isEqualTo(
			"Unable to determine proper version for dependency grafana (version: 7.3.*) "
				+ "from repo 3rd-party-dependencies/kube-prometheus-stack"
		);
	}

	@Test
	void alsoWorksForChartsWithoutDependencies() throws IOException {
		setupForAirgappedUse(null, Collections.emptyList());
		createAirGappedUtils().mirrorHelmRepoToGit(helmConfig);

		GitRepo prometheusRepo = gitRepoFactory.getRepos().get("3rd-party-dependencies/kube-prometheus-stack");
		Map<String, Object> actualPrometheusChartYaml = YAML_MAPPER.readValue(
			Path.of(prometheusRepo.getAbsoluteLocalRepoTmpDir(), "Chart.yaml").toFile(),
			YAML_MAP_TYPE
		);

		Object dependencies = actualPrometheusChartYaml.get("dependencies");
		assertThat(dependencies).isNull();
	}

	@Test
	void failsForInvalidHelmCharts() throws IOException {
		setupForAirgappedUse();

		RuntimeException expectedException = new RuntimeException();
		doThrow(expectedException).when(helmClient).template(anyString(), anyString());

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> createAirGappedUtils().mirrorHelmRepoToGit(helmConfig)
		);

		assertThat(exception.getMessage())
			.isEqualTo("Helm chart in folder " + rootChartsFolder + "/kube-prometheus-stack seems invalid.");
		assertThat(exception.getCause()).isSameAs(expectedException);
	}

	protected void setupForAirgappedUse() throws IOException {
		setupForAirgappedUse(null, null);
	}

	protected void setupForAirgappedUse(Map<String, Object> chartLock) throws IOException {
		setupForAirgappedUse(chartLock, null);
	}

	protected void setupForAirgappedUse(
		Map<String, Object> chartLock,
		List<Map<String, Object>> dependencies
	) throws IOException {
		Path sourceChart = rootChartsFolder.resolve("kube-prometheus-stack");
		Files.createDirectories(sourceChart);

		Map<String, Object> prometheusChartYaml = new LinkedHashMap<>();
		prometheusChartYaml.put("version", "1.2.3");
		prometheusChartYaml.put("name", "kube-prometheus-stack-chart");
		prometheusChartYaml.put("dependencies", List.of(
			Map.of(
				"condition", "crds.enabled",
				"name", "crds",
				"repository", "",
				"version", "0.0.0"
			),
			Map.of(
				"condition", "grafana.enabled",
				"name", "grafana",
				"repository", "https://grafana-repo-url",
				"version", "7.3.*"
			)
		));

		if (dependencies != null) {
			if (dependencies.isEmpty()) {
				prometheusChartYaml.remove("dependencies");
			} else {
				prometheusChartYaml.put("dependencies", dependencies);
			}
		}

		fileSystemUtils.writeYaml(prometheusChartYaml, sourceChart.resolve("Chart.yaml").toFile());

		if (chartLock == null) {
			chartLock = Map.of(
				"dependencies", List.of(
					Map.of(
						"name", "crds",
						"repository", "",
						"version", "0.0.0"
					),
					Map.of(
						"name", "grafana",
						"repository", "https://grafana.github.io/helm-charts",
						"version", "7.3.9"
					)
				)
			);
		}
		fileSystemUtils.writeYaml(chartLock, sourceChart.resolve("Chart.lock").toFile());
	}

	@SuppressWarnings("unchecked")
	protected void assertAirGapped() throws IOException, GitAPIException {
		GitRepo prometheusRepo = gitRepoFactory.getRepos().get("3rd-party-dependencies/kube-prometheus-stack");
		assertThat(prometheusRepo).isNotNull();
		assertThat(Path.of(prometheusRepo.getAbsoluteLocalRepoTmpDir(), "Chart.lock")).doesNotExist();

		Map<String, Object> actualPrometheusChartYaml = YAML_MAPPER.readValue(
			Path.of(prometheusRepo.getAbsoluteLocalRepoTmpDir(), "Chart.yaml").toFile(),
			YAML_MAP_TYPE
		);
		assertThat(actualPrometheusChartYaml.get("name")).isEqualTo("kube-prometheus-stack-chart");

		List<Map<String, Object>> dependencies =
			(List<Map<String, Object>>) actualPrometheusChartYaml.get("dependencies");
		assertThat(dependencies).hasSize(2);
		assertThat(dependencies.get(0).get("name")).isEqualTo("crds");
		assertThat(dependencies.get(0).get("version")).isEqualTo("0.0.0");
		assertThat(dependencies.get(0).get("repository")).isEqualTo("");
		assertThat(dependencies.get(1).get("name")).isEqualTo("grafana");
		assertThat(dependencies.get(1).get("version")).isEqualTo("7.3.9");
		assertThat(dependencies.get(1).get("repository")).isEqualTo("");

		assertHelmRepoCommits(
			prometheusRepo,
			"1.2.3",
			"Chart kube-prometheus-stack-chart, version: 1.2.3\n\n"
				+ "Source: https://kube-prometheus-stack-repo-url\n"
				+ "Dependencies localized to run in air-gapped environments"
		);

		verify(prometheusRepo).createRepositoryAndSetPermission(
			eq("Mirror of Helm chart kube-prometheus-stack from https://kube-prometheus-stack-repo-url"),
			eq(false)
		);
	}

	void assertHelmRepoCommits(GitRepo repo, String expectedTag, String expectedCommitMessage)
		throws IOException, GitAPIException {
		Iterable<RevCommit> commitIterable = Git.open(new File(repo.getAbsoluteLocalRepoTmpDir()))
			.log()
			.setMaxCount(1)
			.all()
			.call();
		List<RevCommit> commits = new ArrayList<>();
		commitIterable.forEach(commits::add);

		assertThat(commits.size()).isEqualTo(1);
		assertThat(commits.get(0).getFullMessage()).isEqualTo(expectedCommitMessage);

		List<Ref> tags = Git.open(new File(repo.getAbsoluteLocalRepoTmpDir())).tagList().call();
		assertThat(tags.size()).isEqualTo(1);
		assertThat(tags.get(0).getName()).isEqualTo("refs/tags/" + expectedTag);
	}

	AirGappedUtils createAirGappedUtils() {
		return new AirGappedUtils(gitRepoFactory, fileSystemUtils, helmClient, gitHandler);
	}
}
