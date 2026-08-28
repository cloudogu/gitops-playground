package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.TestGitProvider;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArgoCDRepoSetupTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};

	private Config config;

	@BeforeEach
	void setUp() {
		config = Config.fromMap(Map.of(
			"application", Map.of(
				"namePrefix", "",
				"tenantName", "",
				"netpols", true,
				"namespaces", Map.of(
					"dedicatedNamespaces", List.of("argocd", "monitoring", "secrets"),
					"tenantNamespaces", List.of("example-apps-staging", "example-apps-production")
				)
			),
			"scm", Map.of(
				"scmProviderType", ScmProviderType.SCM_MANAGER,
				"scmManager", Map.of("internal", true),
				"gitlab", Map.of("url", "")
			),
			"multiTenant", Map.of(
				"scmManager", Map.of("url", ""),
				"gitlab", Map.of("url", ""),
				"useDedicatedInstance", false,
				"centralArgocdNamespace", "argocd"
			),
			"features", Map.of(
				"argocd", Map.of(
					"operator", false,
					"active", true,
					"namespace", "argocd"
				),
				"certManager", Map.of("active", false),
				"ingress", Map.of("active", true),
				"monitoring", Map.of(
					"active", true,
					"helm", Map.of(
						"chart", "kube-prometheus-stack",
						"version", "42.0.3"
					)
				),
				"mail", Map.of("active", false),
				"secrets", Map.of("active", true)
			)
		));
	}

	private ArgoCDRepoSetupTestContext createSetup(FileSystemUtils fs) {
		Map<String, GitProvider> providers = TestGitProvider.buildProviders(config);
		GitProvider tenantProvider = providers.get("tenant");
		GitProvider centralProvider = providers.get("central");

		TestGitRepoFactory repoFactory = new TestGitRepoFactory(config, new FileSystemUtils());

		GitRepo clusterResourcesRepo = repoFactory.create(
			"argocd/cluster-resources",
			Boolean.TRUE.equals(config.getMultiTenant().getUseDedicatedInstance()) ? centralProvider : tenantProvider
		);

		RepositoryWorkspace repositoryWorkspace;

		if (Boolean.TRUE.equals(config.getMultiTenant().getUseDedicatedInstance())) {
			/*
			 * Test-only workspace separation:
			 *
			 * In the real dedicated multi-tenant setup, central cluster-resources and
			 * tenant bootstrap use the same logical repo target in different SCM-Manager
			 * instances. For this unit test, TestGitRepoFactory derives the local workspace
			 * from the repo target. Therefore we use a dedicated test target here to avoid
			 * both GitRepo objects pointing to the same local directory.
			 */
			GitRepo tenantBootstrapRepo = repoFactory.create(
				"argocd/tenant-bootstrap-cluster-resources",
				tenantProvider
			);

			repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo, tenantBootstrapRepo);
		} else {
			repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo);
		}

		GitHandlerForTests gitHandler = new GitHandlerForTests(tenantProvider, centralProvider);

		DeploymentContext context = new ContextBuilder(config).build();
		return new ArgoCDRepoSetupTestContext(
			ArgoCDRepoSetup.create(
				fs,
				gitHandler,
				repositoryWorkspace,
				new ArgoCDToolConfigMapper(config).map(context)
			),
			repositoryWorkspace
		);
	}

	@Test
	void createSingleInstanceUsesClusterResourcesRepositoryOnly() {
		config.getMultiTenant().setUseDedicatedInstance(false);

		ArgoCDRepoSetupTestContext testContext = createSetup(new FileSystemUtils());

		assertThat(testContext.repositoryWorkspace.getClusterResourcesRepository()).isNotNull();
		assertThat(testContext.repositoryWorkspace.getClusterResourcesRepository().getRepoTarget())
			.isEqualTo("argocd/cluster-resources");
		assertThat(testContext.repositoryWorkspace.hasTenantBootstrapRepository()).isFalse();

		assertThat(testContext.setup.clusterRepoLayout()).isNotNull();
	}

	@Test
	void createDedicatedInstanceUsesClusterResourcesAndTenantBootstrapRepositoriesFromWorkspace() {
		config.getMultiTenant().setUseDedicatedInstance(true);

		ArgoCDRepoSetupTestContext testContext = createSetup(new FileSystemUtils());

		assertThat(testContext.repositoryWorkspace.getClusterResourcesRepository()).isNotNull();
		assertThat(testContext.repositoryWorkspace.getTenantBootstrapRepository()).isNotNull();
		assertThat(testContext.repositoryWorkspace.hasTenantBootstrapRepository()).isTrue();

		assertThat(testContext.setup.clusterRepoLayout()).isNotNull();
		assertThat(testContext.setup.tenantRepoLayout()).isNotNull();
	}

	@Test
	void dedicatedModeUsesSeparateLocalWorkspacesForCentralAndTenantBootstrapRepositories() throws IOException {
		config.getMultiTenant().setUseDedicatedInstance(true);

		ArgoCDRepoSetupTestContext testContext = createSetup(new FileSystemUtils());

		assertThat(new File(testContext.repositoryWorkspace.clusterResourcesRootDir()).getCanonicalPath())
			.isNotEqualTo(new File(testContext.repositoryWorkspace.tenantBootstrapRootDir()).getCanonicalPath());
	}

	@Test
	void tenantRepoLayoutThrowsInSingleInstanceMode() {
		config.getMultiTenant().setUseDedicatedInstance(false);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		assertThrows(IllegalStateException.class, setup::tenantRepoLayout);
	}

	@Test
	void tenantRepoLayoutIsAvailableInDedicatedInstanceMode() {
		config.getMultiTenant().setUseDedicatedInstance(true);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		assertThat(setup.tenantRepoLayout()).isNotNull();
	}

	@Test
	void prepareRepositoriesDeletesHelmDirWhenOperatorIsEnabled() {
		config.getFeatures().getArgocd().setOperator(true);
		config.getMultiTenant().setUseDedicatedInstance(false);
		config.getApplication().setNetpols(true);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = setup.clusterRepoLayout();

		assertThat(Path.of(clusterRepoLayout.helmDir())).doesNotExist();
	}

	@Test
	void prepareRepositoriesDeletesOperatorDirWhenOperatorIsDisabled() {
		config.getFeatures().getArgocd().setOperator(false);
		config.getMultiTenant().setUseDedicatedInstance(false);
		config.getApplication().setNetpols(true);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = setup.clusterRepoLayout();

		assertThat(Path.of(clusterRepoLayout.operatorDir())).doesNotExist();
		assertThat(Path.of(clusterRepoLayout.helmDir())).exists();
	}

	@Test
	void prepareRepositoriesInDedicatedModeReplacesSingleInstanceResourcesWithCentralResources() {
		config.getFeatures().getArgocd().setOperator(false);
		config.getMultiTenant().setUseDedicatedInstance(true);
		config.getApplication().setNetpols(true);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = setup.clusterRepoLayout();

		assertThat(Path.of(clusterRepoLayout.applicationsDir())).exists();
		assertThat(Path.of(clusterRepoLayout.projectsDir())).exists();
		assertThat(Path.of(clusterRepoLayout.multiTenantDir())).doesNotExist();
	}

	@Test
	@SuppressWarnings("unchecked")
	void prepareRepositoriesInDedicatedModeKeepsCentralAndTenantBootstrapTemplatesSeparated() throws IOException {
		config.getApplication().setNamePrefix("testPrefix-");
		config.getMultiTenant().setUseDedicatedInstance(true);
		config.getMultiTenant().getScmManager().setUrl("scmm.testhost/scm");
		config.getMultiTenant().setCentralArgocdNamespace("argocd");
		config.getFeatures().getArgocd().setOperator(true);

		ArgoCDRepoSetupTestContext testContext = createSetup(new FileSystemUtils());

		testContext.setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = testContext.setup.clusterRepoLayout();
		ArgoCDRepoLayout tenantRepoLayout = testContext.setup.tenantRepoLayout();

		File centralBootstrapFile = new File(clusterRepoLayout.applicationsDir(), "bootstrap.yaml");
		File tenantBootstrapFile = new File(tenantRepoLayout.applicationsDir(), "bootstrap.yaml");

		assertThat(centralBootstrapFile).exists();
		assertThat(tenantBootstrapFile).exists();

		Map<String, Object> centralBootstrapYaml = YAML_MAPPER.readValue(centralBootstrapFile, YAML_MAP_TYPE);
		List<Map<String, Object>> tenantBootstrapYaml;
		try (MappingIterator<Map<String, Object>> documents = YAML_MAPPER
			.readerFor(YAML_MAP_TYPE)
			.readValues(tenantBootstrapFile)) {
			tenantBootstrapYaml = documents.readAll();
		}

		assertThat(centralBootstrapYaml)
			.as("central bootstrap.yaml must contain exactly one central Application")
			.isInstanceOf(Map.class);

		Map<String, Object> centralMetadata = (Map<String, Object>) centralBootstrapYaml.get("metadata");
		Map<String, Object> centralSpec = (Map<String, Object>) centralBootstrapYaml.get("spec");
		Map<String, Object> centralDestination = (Map<String, Object>) centralSpec.get("destination");
		Map<String, Object> centralSource = (Map<String, Object>) centralSpec.get("source");

		assertThat(centralMetadata.get("name")).isEqualTo("testPrefix-bootstrap");
		assertThat(centralMetadata.get("namespace")).isEqualTo("argocd");
		assertThat(centralDestination.get("namespace")).isEqualTo("testPrefix-argocd");
		assertThat(centralSpec.get("project")).isEqualTo("testPrefix");
		assertThat(centralSource.get("path")).isEqualTo("apps/argocd/applications/");
		assertThat(centralSource.get("repoURL"))
			.isEqualTo("scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources.git");

		assertThat(tenantBootstrapYaml)
			.as("tenant bootstrap.yaml should contain tenant bootstrap Applications")
			.isInstanceOf(List.class);

		List<Map<String, Object>> tenantBootstrapDocuments = tenantBootstrapYaml;

		List<String> tenantApplicationNames = tenantBootstrapDocuments.stream()
																	  .map(document -> (String) ((Map<String, Object>) document.get(
																		  "metadata")).get("name"))
																	  .collect(Collectors.toList());

		List<String> tenantApplicationNamespaces = tenantBootstrapDocuments.stream()
																		   .map(document -> (String) ((Map<String, Object>) document.get(
																			   "metadata")).get("namespace"))
																		   .collect(Collectors.toList());

		List<String> tenantApplicationProjects = tenantBootstrapDocuments.stream()
																		 .map(document -> (String) ((Map<String, Object>) document.get(
																			 "spec")).get("project"))
																		 .collect(Collectors.toList());

		assertThat(tenantApplicationNames).containsExactly("bootstrap", "projects");
		assertThat(tenantApplicationNamespaces).containsOnly("testPrefix-argocd");
		assertThat(tenantApplicationProjects).containsOnly("argocd");
	}

	@Test
	void prepareRepositoriesInSingleInstanceDeletesMultiTenantFolder() {
		config.getFeatures().getArgocd().setOperator(false);
		config.getMultiTenant().setUseDedicatedInstance(false);
		config.getApplication().setNetpols(true);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = setup.clusterRepoLayout();

		assertThat(Path.of(clusterRepoLayout.multiTenantDir())).doesNotExist();
	}

	@Test
	void prepareRepositoriesDeletesNetpolFileWhenNetpolsDisabled() {
		config.getApplication().setNetpols(false);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = setup.clusterRepoLayout();

		assertThat(Path.of(clusterRepoLayout.netpolFile())).doesNotExist();
	}

	@Test
	void prepareRepositoriesKeepsNetpolFileWhenNetpolsEnabled() {
		config.getApplication().setNetpols(true);

		ArgoCDRepoSetup setup = createSetup(new FileSystemUtils()).setup;

		setup.prepareRepositories();

		ArgoCDRepoLayout clusterRepoLayout = setup.clusterRepoLayout();

		assertThat(Path.of(clusterRepoLayout.netpolFile())).exists();
	}

	@Test
	void prepareRepositoriesPreparesTenantBootstrapRepositoryInDedicatedMode() {
		config.getMultiTenant().setUseDedicatedInstance(true);

		ArgoCDRepoSetupTestContext testContext = createSetup(new FileSystemUtils());

		testContext.setup.prepareRepositories();

		assertThat(Path.of(testContext.repositoryWorkspace.tenantBootstrapRootDir())).exists();
		assertThat(Path.of(testContext.repositoryWorkspace.tenantBootstrapRootDir()).toFile().listFiles()).isNotEmpty();
	}

	@Test
	void prepareRepositoriesDoesNotPrepareTenantBootstrapRepositoryInSingleInstanceMode() {
		config.getMultiTenant().setUseDedicatedInstance(false);

		ArgoCDRepoSetupTestContext testContext = createSetup(new FileSystemUtils());

		testContext.setup.prepareRepositories();

		assertThat(testContext.repositoryWorkspace.hasTenantBootstrapRepository()).isFalse();
	}

	static class ArgoCDRepoSetupTestContext {
		ArgoCDRepoSetup setup;
		RepositoryWorkspace repositoryWorkspace;

		ArgoCDRepoSetupTestContext(ArgoCDRepoSetup setup, RepositoryWorkspace repositoryWorkspace) {
			this.setup = setup;
			this.repositoryWorkspace = repositoryWorkspace;
		}
	}
}
