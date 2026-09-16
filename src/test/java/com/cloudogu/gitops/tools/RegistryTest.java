package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.utils.AirGappedUtils;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.K8sClientForTest;
import com.cloudogu.gitops.utils.YamlUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.cloudogu.gitops.config.Config.DEFAULT_REGISTRY_PORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

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

	private Registry createRegistry() {
		return createRegistry(new Config.RegistrySchema());
	}

	private Registry createRegistry(Config.RegistrySchema registryConfig) {
		Config config = createConfig(registryConfig);
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
		deploymentContext = createContext(registryConfig);
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
