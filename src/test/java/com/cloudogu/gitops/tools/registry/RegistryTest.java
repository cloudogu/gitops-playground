package com.cloudogu.gitops.tools.registry;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.K8sClientForTest;
import com.cloudogu.gitops.utils.YamlUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cloudogu.gitops.config.Config.DEFAULT_REGISTRY_PORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistryTest {

	private K8sClientForTest k8sClient;
	private Path temporaryYamlFile;
	private HelmClient helmClient;
	private DeploymentContext deploymentContext;

	@Mock
	private Deployer deployer;

	@Mock
	private RepositoryWorkspace repositoryWorkspace;

	@Mock
	private GitRepo clusterResourcesRepo;

	@TempDir
	Path clusterResourcesDir;

	@Test
	void isDisabledWhenExternalRegistryIsConfigured() {
		Config.RegistrySchema registryConfig = new Config.RegistrySchema();

		assertFalse(createRegistry(registryConfig).isEnabled(createContext(registryConfig)));
	}

	@Test
	void isInstalled() throws IOException, GitAPIException {
		Config.RegistrySchema registryConfig = new Config.RegistrySchema();
		registryConfig.setActive(true);
		registryConfig.setInternal(true);

		install(createRegistry(registryConfig), registryConfig);

		Map<String, Object> actualYaml = parseActualYaml();
		Map<?, ?> service = (Map<?, ?>) actualYaml.get("service");
		assertThat(service.get("nodePort")).isEqualTo(DEFAULT_REGISTRY_PORT);
		assertThat(service.get("type")).isEqualTo("NodePort");

		verify(deployer).deployFeature(
			anyString(),
			eq("registry"),
			eq("docker-registry"),
			anyString(),
			eq("foo-registry"),
			eq("docker-registry"),
			any(Path.class),
			eq(RepoType.HELM),
			eq(true),
			eq(deploymentContext),
			eq(repositoryWorkspace)
		);

		verify(repositoryWorkspace).commitAndPushClusterResourcesChanges("Update registry GitOps resources");
	}

	@Test
	void injectCustomValueIntoChart() throws IOException, GitAPIException {
		Config.RegistrySchema registryConfig = new Config.RegistrySchema();
		registryConfig.setActive(true);
		registryConfig.setInternal(true);

		Config.HelmConfigWithValues helm = new Config.HelmConfigWithValues();
		helm.setChart("test");

		Map<String, Object> service = new LinkedHashMap<>();
		service.put("type", "NodePortTest");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("service", service);
		values.put("customValue", "testinjectionValue");
		helm.setValues(values);
		registryConfig.setHelm(helm);

		install(createRegistry(registryConfig), registryConfig);

		assertThat(String.valueOf(parseActualYaml().get("service"))).contains("NodePortTest");
		assertThat(String.valueOf(parseActualYaml().get("customValue"))).contains("testinjectionValue");

		verify(repositoryWorkspace).commitAndPushClusterResourcesChanges("Update registry GitOps resources");
	}

	@Test
	void createsNetworkPolicyForConfiguredRegistryAccessCidrs() throws IOException, GitAPIException {
		Config.RegistrySchema registryConfig = new Config.RegistrySchema();
		registryConfig.setActive(true);
		registryConfig.setInternal(true);
		Config config = createConfig(registryConfig);
		config.getApplication().setNetpols(true);
		config.getApplication().getNetworkPolicies().setRegistryAccessCidrs(
			List.of("192.168.10.0/24", "10.0.0.5/32")
		);
		Registry registry = createRegistry(config);
		install(registry, config);

		ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
		verify(clusterResourcesRepo).writeFile(
			eq("apps/registry/netpols/allow-required-access-to-registry.yaml"),
			yaml.capture()
		);
		assertThat(yaml.getValue())
			.contains("name: allow-required-access-to-registry")
			.contains("namespace: foo-registry")
			.contains("app: docker-registry")
			.contains("release: docker-registry")
			.contains("cidr: 192.168.10.0/24")
			.contains("cidr: 10.0.0.5/32")
			.contains("port: 5000");
	}

	@Test
	void createsDenyIngressPolicyWhenNoRegistryAccessCidrsAreConfigured() throws IOException, GitAPIException {
		Config.RegistrySchema registryConfig = new Config.RegistrySchema();
		registryConfig.setActive(true);
		registryConfig.setInternal(true);
		Config config = createConfig(registryConfig);
		config.getApplication().setNetpols(true);

		Registry registry = createRegistry(config);
		install(registry, config);

		ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
		verify(clusterResourcesRepo).writeFile(
			eq("apps/registry/netpols/allow-required-access-to-registry.yaml"),
			yaml.capture()
		);
		assertThat(yaml.getValue())
			.contains("name: allow-required-access-to-registry")
			.contains("ingress: []");
	}

	@Test
	void removesGeneratedNetworkPolicyWhenNetworkPoliciesAreDisabled() throws IOException, GitAPIException {
		Path networkPolicy = clusterResourcesDir.resolve(
			"apps/registry/netpols/allow-required-access-to-registry.yaml"
		);
		Files.createDirectories(networkPolicy.getParent());
		Files.writeString(networkPolicy, "stale policy");

		Config.RegistrySchema registryConfig = new Config.RegistrySchema();
		registryConfig.setActive(true);
		registryConfig.setInternal(true);

		install(createRegistry(registryConfig), registryConfig);

		assertThat(networkPolicy).doesNotExist();
	}

	private Registry createRegistry() {
		return createRegistry(new Config.RegistrySchema());
	}

	private Registry createRegistry(Config.RegistrySchema registryConfig) {
		return createRegistry(createConfig(registryConfig));
	}

	private Registry createRegistry(Config config) {
		k8sClient = new K8sClientForTest();

		FileSystemUtils fileUtil = new FileSystemUtils() {
			@Override
			public Path writeTempFile(Map<String, Object> mergeMap) {
				Path result = super.writeTempFile(mergeMap);
				temporaryYamlFile = Path.of(result.toString().replace(".ftl", ""));
				return result;
			}
		};

		AirGappedUtils airGappedUtils = new AirGappedUtils(null, fileUtil, helmClient, null);

		return new Registry(fileUtil, k8sClient, airGappedUtils, deployer, new RegistryToolConfigMapper(config));
	}

	private boolean install(Registry registry, Config.RegistrySchema registryConfig) {
		return install(registry, createConfig(registryConfig));
	}

	private boolean install(Registry registry, Config config) {
		when(repositoryWorkspace.getClusterResourcesRepository()).thenReturn(clusterResourcesRepo);
		when(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()).thenReturn(clusterResourcesDir.toString());
		deploymentContext = new ContextBuilder(config).build();
		return registry.execute(deploymentContext, repositoryWorkspace);
	}

	private DeploymentContext createContext(Config.RegistrySchema registryConfig) {
		return new ContextBuilder(createConfig(registryConfig)).build();
	}

	private Config createConfig(Config.RegistrySchema registryConfig) {
		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setNamePrefix("foo-");

		Config config = new Config();
		config.setApplication(application);
		config.setRegistry(registryConfig);
		return config;
	}

	private Map<String, Object> parseActualYaml() throws IOException {
		return YamlUtils.parseYamlMap(Files.readString(temporaryYamlFile));
	}
}
