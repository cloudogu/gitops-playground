package com.cloudogu.gitops.tools

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator

import groovy.transform.CompileStatic

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@CompileStatic
@ExtendWith(MockitoExtension)
class IngressTest {

	private Config config
	private DeploymentContext deploymentContext
	private RepositoryWorkspace repositoryWorkspace

	@Mock
	private HelmToolDeployer helmToolDeployer

	@Mock
	private K8sClient k8sClient

	@Mock
	private ImagePullSecretCreator imagePullSecretCreator

	@Mock
	private GitRepo clusterResourcesRepository

	@BeforeEach
	void setUp() {
		config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'),
			features: new Config.FeaturesSchema(ingress: new Config.IngressSchema(active: true)))

		deploymentContext = new ContextBuilder(config).build()
	}

	@Test
	void 'is enabled when ingress is active'() {
		assertThat(createIngress().isEnabled(deploymentContext)).isTrue()
	}

	@Test
	void 'is disabled when ingress is inactive'() {
		config.features.ingress.active = false

		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createIngress().isEnabled(context)).isFalse()
	}

	@Test
	void 'creates expected Helm deployment request'() {
		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.toolName)
			.isEqualTo('traefik')

		assertThat(request.releaseName)
			.isEqualTo('traefik')

		assertThat(request.namespace)
			.isEqualTo("foo-${config.features.ingress.ingressNamespace}")

		assertThat(request.helmConfig)
			.isSameAs(config.features.ingress.helm)

		assertThat(request.helmValuesPath)
			.isEqualTo(Ingress.HELM_VALUES_PATH)

		assertThat(request.bootstrapWithHelm)
			.isFalse()
	}

	@Test
	void 'uses configured namespace prefix'() {
		config.application.namePrefix = 'tenant-'

		deploymentContext = new ContextBuilder(config).build()

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.namespace)
			.isEqualTo("tenant-${config.features.ingress.ingressNamespace}")
	}

	@Test
	void 'returns active namespace when ingress is enabled'() {
		assertThat(createIngress()
			.getActiveNamespace(deploymentContext)).isEqualTo("foo-${config.features.ingress.ingressNamespace}")
	}

	@Test
	void 'returns no namespace when ingress is disabled'() {
		config.features.ingress.active = false

		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createIngress()
			.getActiveNamespace(context)).isNull()
	}

	@Test
	void 'passes configured Helm values unchanged'() {
		config.features.ingress.helm.values = [controller: [replicaCount: 42,
		                                                    span        : '7,5']]

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.helmConfig.values)
			.isEqualTo([controller: [replicaCount: 42,
			                         span        : '7,5']])
	}

	@Test
	void 'creates image pull secret in ingress namespace'() {
		executeIngress()

		verify(imagePullSecretCreator)
			.createIfRequired(config,
				"foo-${config.features.ingress.ingressNamespace}")
	}

	@Test
	void 'delegates deployment to HelmToolDeployer'() {
		executeIngress()

		verify(helmToolDeployer)
			.deploy(any(HelmToolDeploymentRequest),
				eq(deploymentContext),
				eq(repositoryWorkspace))
	}

	@Test
	void 'publishes Traefik GitOps resources'() {
		executeIngress()

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges('Update traefik GitOps resources')
	}

	private HelmToolDeploymentRequest executeAndCaptureRequest() {
		executeIngress()

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer)
			.deploy(captor.capture(),
				eq(deploymentContext),
				eq(repositoryWorkspace))

		return captor.value
	}

	private boolean executeIngress() {
		createMockWorkspace()

		deploymentContext = new ContextBuilder(config).build()

		return createIngress().execute(deploymentContext,
			repositoryWorkspace)
	}

	private Ingress createIngress() {
		return new Ingress(helmToolDeployer,
			imagePullSecretCreator)
	}

	private void createMockWorkspace() {
		repositoryWorkspace = mock(RepositoryWorkspace)

		when(repositoryWorkspace
			.clusterResourcesRepository).thenReturn(clusterResourcesRepository)

		when(clusterResourcesRepository.repoTarget).thenReturn('argocd/cluster-resources')
	}
}