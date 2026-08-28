package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.config.scm.ScmTenantSchema.ScmManagerTenantConfig;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArgoCdApplicationStrategyTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};

	private File localTempDir;
	private DeploymentContext context;
	private RepositoryWorkspace repositoryWorkspace;

	@Test
	void deploysFeatureUsingArgoCd() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy();
		File valuesYaml = File.createTempFile("values", "yaml");

		strategy.deployFeature(
			"repoURL",
			"repoName",
			"chartName",
			"version",
			"foo-namespace",
			"releaseName",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		File argoCdApplicationYaml = new File(localTempDir, "apps/argocd/applications/releaseName.yaml");

		assertThat(Files.readString(argoCdApplicationYaml.toPath())).isEqualTo("""
			---
			apiVersion: "argoproj.io/v1alpha1"
			kind: "Application"
			metadata:
			  name: "foo-repoName"
			  namespace: "foo-argocd"
			spec:
			  destination:
			    server: "https://kubernetes.default.svc"
			    namespace: "foo-namespace"
			  project: "cluster-resources"
			  sources:
			  - repoURL: "repoURL"
			    chart: "chartName"
			    targetRevision: "version"
			    helm:
			      releaseName: "releaseName"
			      valueFiles:
			      - "$values/apps/repoName/repoName-gop-helm.yaml"
			      - "$values/apps/repoName/repoName-user-values.yaml"
			      ignoreMissingValueFiles: true
			  - repoURL: "http://scmm.scm-manager.svc.cluster.local/scm/repo/argocd/cluster-resources.git"
			    targetRevision: "main"
			    ref: "values"
			    path: "apps/repoName"
			    directory:
			      recurse: true
			  syncPolicy:
			    automated:
			      prune: true
			      selfHeal: true
			    syncOptions:
			    - "ServerSideApply=true"
			    - "CreateNamespace=true"
			""");
	}

	@Test
	@SuppressWarnings("unchecked")
	void deploysFeatureUsingArgoCdFromGitRepo() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy();
		File valuesYaml = File.createTempFile("values", "yaml");

		strategy.deployFeature(
			"repoURL",
			"repoName",
			"chartName",
			"version",
			"namespace",
			"releaseName",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.GIT,
			context,
			repositoryWorkspace
		);

		File argoCdApplicationYaml = new File(localTempDir, "apps/argocd/applications/releaseName.yaml");
		Map<String, Object> result = YAML_MAPPER.readValue(argoCdApplicationYaml, YAML_MAP_TYPE);
		Map<String, Object> spec = (Map<String, Object>) result.get("spec");
		List<Map<String, Object>> sources = (List<Map<String, Object>>) spec.get("sources");

		assertThat(sources.get(0)).containsKey("path");
		assertThat(sources.get(0).get("path")).isEqualTo("chartName");
	}

	@Test
	void deploysFeatureWithArgoCdOperatorTrueSettingCreateNamespaceToFalse() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy(true);
		File valuesYaml = File.createTempFile("values", "yaml");
		Files.writeString(valuesYaml.toPath(), """
			param1: value1
			param2: value2
			""");

		strategy.deployFeature(
			"repoURL",
			"repoName",
			"chartName",
			"version",
			"namespace",
			"releaseName",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		File argoCdApplicationYaml = new File(localTempDir, "apps/argocd/applications/releaseName.yaml");

		assertThat(Files.readString(argoCdApplicationYaml.toPath())).contains("CreateNamespace=false");
	}

	@Test
	void deploysFeatureWithArgoCdOperatorFalseSettingCreateNamespaceToTrue() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy(false);
		File valuesYaml = File.createTempFile("values", "yaml");
		Files.writeString(valuesYaml.toPath(), """
			param1: value1
			param2: value2
			""");

		strategy.deployFeature(
			"repoURL",
			"repoName",
			"chartName",
			"version",
			"namespace",
			"releaseName",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		File argoCdApplicationYaml = new File(localTempDir, "apps/argocd/applications/releaseName.yaml");

		assertThat(Files.readString(argoCdApplicationYaml.toPath())).contains("CreateNamespace=true");
	}

	@Test
	@SuppressWarnings("unchecked")
	void deploysScmManagerAsBootstrapApplicationWithoutValuesSource() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy();
		File valuesYaml = File.createTempFile("values", "yaml");
		Files.writeString(valuesYaml.toPath(), """
			fullnameOverride: tenant1-scmm
			service:
			  type: NodePort
			""");

		strategy.deployFeature(
			"repoURL",
			"scm-manager",
			"scm-manager",
			"3.11.6",
			"tenant1-scm-manager",
			"tenant1-scmm",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		File argoCdApplicationYaml = new File(localTempDir, "apps/argocd/applications/tenant1-scmm.yaml");
		Map<String, Object> result = YAML_MAPPER.readValue(argoCdApplicationYaml, YAML_MAP_TYPE);
		Map<String, Object> spec = (Map<String, Object>) result.get("spec");
		List<Map<String, Object>> sources = (List<Map<String, Object>>) spec.get("sources");
		Map<String, Object> helm = (Map<String, Object>) sources.get(0).get("helm");

		assertThat(sources).hasSize(1);
		assertThat(sources.get(0).get("repoURL")).isEqualTo("repoURL");
		assertThat(sources.get(0).get("chart")).isEqualTo("scm-manager");
		assertThat(helm.get("releaseName")).isEqualTo("tenant1-scmm");
		assertThat(helm.get("values").toString()).contains("fullnameOverride: tenant1-scmm");
	}

	@Test
	void deploysScmManagerAsBootstrapApplicationWithoutWritingExternalValueFiles() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy();
		File valuesYaml = File.createTempFile("values", "yaml");
		Files.writeString(valuesYaml.toPath(), """
			fullnameOverride: tenant1-scmm
			""");

		strategy.deployFeature(
			"repoURL",
			"scm-manager",
			"scm-manager",
			"3.11.6",
			"tenant1-scm-manager",
			"tenant1-scmm",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		assertThat(new File(localTempDir, "apps/scm-manager/scm-manager-gop-helm.yaml")).doesNotExist();
		assertThat(new File(localTempDir, "apps/scm-manager/scm-manager-user-values.yaml")).doesNotExist();
	}

	@Test
	void deploysNormalFeatureWithGopAndUserValuesFiles() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy();
		File valuesYaml = File.createTempFile("values", "yaml");
		Files.writeString(valuesYaml.toPath(), """
			param1: value1
			""");

		strategy.deployFeature(
			"repoURL",
			"repoName",
			"chartName",
			"version",
			"namespace",
			"releaseName",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		assertThat(Files.readString(new File(localTempDir, "apps/repoName/repoName-gop-helm.yaml").toPath()))
			.contains("param1: value1");

		assertThat(new File(localTempDir, "apps/repoName/repoName-user-values.yaml")).exists();
	}

	@Test
	@SuppressWarnings("unchecked")
	void usesWorkspaceClusterResourcesRepositoryAsValuesSource() throws IOException {
		ArgoCdApplicationStrategy strategy = createStrategy();
		File valuesYaml = File.createTempFile("values", "yaml");

		strategy.deployFeature(
			"repoURL",
			"repoName",
			"chartName",
			"version",
			"namespace",
			"releaseName",
			valuesYaml.toPath(),
			DeploymentStrategy.RepoType.HELM,
			context,
			repositoryWorkspace
		);

		File argoCdApplicationYaml = new File(localTempDir, "apps/argocd/applications/releaseName.yaml");
		Map<String, Object> result = YAML_MAPPER.readValue(argoCdApplicationYaml, YAML_MAP_TYPE);
		Map<String, Object> spec = (Map<String, Object>) result.get("spec");
		List<Map<String, Object>> sources = (List<Map<String, Object>>) spec.get("sources");

		assertThat(sources.get(1).get("repoURL"))
			.isEqualTo("http://scmm.scm-manager.svc.cluster.local/scm/repo/argocd/cluster-resources.git");

		assertThat(sources.get(1).get("path")).isEqualTo("apps/repoName");
	}

	private ArgoCdApplicationStrategy createStrategy() {
		return createStrategy(false);
	}

	private ArgoCdApplicationStrategy createStrategy(boolean argocdOperator) {
		Config config = new Config();

		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setNamePrefix("foo-");
		application.setGitName("Cloudogu");
		application.setGitEmail("hello@cloudogu.com");
		config.setApplication(application);

		ScmManagerTenantConfig scmManager = new ScmManagerTenantConfig();
		scmManager.setUsername("dont-care-username");
		scmManager.setPassword("dont-care-password");
		ScmTenantSchema scm = new ScmTenantSchema();
		scm.setScmManager(scmManager);
		config.setScm(scm);

		Config.ArgoCDSchema argoCd = new Config.ArgoCDSchema();
		argoCd.setOperator(argocdOperator);
		Config.FeaturesSchema features = new Config.FeaturesSchema();
		features.setArgocd(argoCd);
		config.setFeatures(features);

		ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();

		TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, new FileSystemUtils()) {
			@Override
			public GitRepo create(String repoTarget, GitProvider scm) {
				GitRepo repo = super.create(repoTarget, scmManagerMock);

				assertThat(repo)
					.as("TestGitRepoFactory must create cluster-resources GitRepo")
					.isNotNull();

				localTempDir = new File(repo.getAbsoluteLocalRepoTmpDir());

				return repo;
			}
		};

		GitRepo clusterResourcesRepo = repoProvider.create("argocd/cluster-resources", scmManagerMock);

		repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo);
		context = new ContextBuilder(config).build();

		ArgoCdApplicationTargetResolver targetResolver = new ArgoCdApplicationTargetResolver(config);

		return new ArgoCdApplicationStrategy(targetResolver);
	}
}
