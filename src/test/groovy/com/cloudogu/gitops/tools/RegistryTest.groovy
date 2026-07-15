package com.cloudogu.gitops.tools

import static com.cloudogu.gitops.config.Config.*
import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.verify

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient

import groovy.transform.CompileStatic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@CompileStatic
@ExtendWith(MockitoExtension)
class RegistryTest {

	@Mock
	HelmToolDeployer helmToolDeployer

	@Mock
	K8sClient k8sClient

	@Mock
	RepositoryWorkspace repositoryWorkspace

	private DeploymentContext deploymentContext

	@Test
	void 'is enabled when active internal registry is configured'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true)

		assertTrue(createRegistry()
			.isEnabled(createContext(registryConfig)))
	}

	@Test
	void 'is disabled when external registry is configured'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: false)

		assertFalse(createRegistry()
			.isEnabled(createContext(registryConfig)))
	}

	@Test
	void 'is disabled when registry is inactive'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: false,
				internal: true)

		assertFalse(createRegistry()
			.isEnabled(createContext(registryConfig)))
	}

	@Test
	void 'creates expected Helm deployment request'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true,
				helm: new HelmConfigWithValues(chart: 'docker-registry',
					repoURL: 'https://helm.twun.io',
					version: '2.2.3'))

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest(registryConfig)

		assertThat(request.toolName)
			.isEqualTo('registry')

		assertThat(request.releaseName)
			.isEqualTo('docker-registry')

		assertThat(request.namespace)
			.isEqualTo('foo-registry')

		assertThat(request.helmConfig.chart)
			.isEqualTo('docker-registry')

		assertThat(request.helmConfig.repoURL)
			.isEqualTo('https://helm.twun.io')

		assertThat(request.helmConfig.version)
			.isEqualTo('2.2.3')

		assertThat(request.helmValuesPath)
			.isEmpty()

		assertThat(request.bootstrapWithHelm)
			.isTrue()
	}

	@Test
	void 'adds default registry service configuration to template data'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true)

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest(registryConfig)

		Map<String, Object> service =
			request.templateData['service']
				as Map<String, Object>

		assertThat(service['nodePort'])
			.isEqualTo(DEFAULT_REGISTRY_PORT)

		assertThat(service['type'])
			.isEqualTo('NodePort')
	}

	@Test
	void 'keeps custom Helm values in request'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true,
				helm: new HelmConfigWithValues(chart: 'test',
					repoURL: 'https://example.org/charts',
					version: '1.0.0',
					values: [service    : [type: 'NodePortTest'],
					         customValue: 'testinjectionValue']))

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest(registryConfig)

		assertThat(request.helmConfig.values)
			.isEqualTo([service    : [type: 'NodePortTest'],
			            customValue: 'testinjectionValue'])

		assertThat(request.templateData['service'])
			.isEqualTo([nodePort: DEFAULT_REGISTRY_PORT,
			            type    : 'NodePort'])
	}

	@Test
	void 'uses configured namespace prefix'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true)

		Config config =
			createConfig(registryConfig)

		config.application.namePrefix = 'tenant-'

		deploymentContext = new ContextBuilder(config).build()

		Registry registry =
			createRegistry()

		registry.execute(deploymentContext,
			repositoryWorkspace)

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer).deploy(captor.capture(),
			eq(deploymentContext),
			eq(repositoryWorkspace))

		assertThat(captor.value.namespace)
			.isEqualTo('tenant-registry')
	}

	@Test
	void 'delegates deployment to HelmToolDeployer'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true)

		install(createRegistry(),
			registryConfig)

		verify(helmToolDeployer).deploy(any(HelmToolDeploymentRequest),
			eq(deploymentContext),
			eq(repositoryWorkspace))
	}

	@Test
	void 'publishes generated registry GitOps resources'() {
		RegistrySchema registryConfig =
			new RegistrySchema(active: true,
				internal: true)

		install(createRegistry(),
			registryConfig)

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges('Update registry GitOps resources')
	}

	private HelmToolDeploymentRequest executeAndCaptureRequest(RegistrySchema registryConfig) {
		install(createRegistry(),
			registryConfig)

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer).deploy(captor.capture(),
			eq(deploymentContext),
			eq(repositoryWorkspace))

		return captor.value
	}

	private Registry createRegistry() {
		return new Registry(helmToolDeployer,
			k8sClient)
	}

	private boolean install(Registry registry,
		RegistrySchema registryConfig) {
		deploymentContext = createContext(registryConfig)

		return registry.execute(deploymentContext,
			repositoryWorkspace)
	}

	private DeploymentContext createContext(RegistrySchema registryConfig) {
		return new ContextBuilder(createConfig(registryConfig)).build()
	}

	private Config createConfig(RegistrySchema registryConfig) {
		return new Config(application: new ApplicationSchema(namePrefix: 'foo-'),
			registry: registryConfig)
	}
}