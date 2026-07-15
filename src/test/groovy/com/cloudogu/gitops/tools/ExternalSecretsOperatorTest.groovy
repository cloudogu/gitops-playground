package com.cloudogu.gitops.tools

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.utils.FileSystemUtils

import groovy.transform.CompileStatic

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

@CompileStatic
class ExternalSecretsOperatorTest {

	private Config config

	private final FileSystemUtils fileSystemUtils =
		new FileSystemUtils()

	private final HelmToolDeployer helmToolDeployer =
		mock(HelmToolDeployer)

	private final ImagePullSecretCreator imagePullSecretCreator =
		mock(ImagePullSecretCreator)

	private ScmManagerProviderMock scmManagerMock
	private RepositoryWorkspace repositoryWorkspace
	private DeploymentContext deploymentContext
	private File clusterResourcesRepoDir

	@BeforeEach
	void setUp() {
		config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'),
			registry: new Config.RegistrySchema(),
			features: new Config.FeaturesSchema(secrets: new Config.SecretsSchema(active: true)))

		scmManagerMock = new ScmManagerProviderMock()
	}

	@Test
	void 'is enabled when secrets feature is active'() {
		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createExternalSecretsOperator()
			.isEnabled(context)).isTrue()
	}

	@Test
	void 'is disabled when secrets feature is inactive'() {
		config.features.secrets.active = false

		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createExternalSecretsOperator()
			.isEnabled(context)).isFalse()
	}

	@Test
	void 'creates expected Helm deployment request'() {
		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.toolName)
			.isEqualTo('external-secrets')

		assertThat(request.releaseName)
			.isEqualTo('external-secrets')

		assertThat(request.namespace)
			.isEqualTo('foo-secrets')

		assertThat(request.helmConfig)
			.isSameAs(config.features
				.secrets
				.externalSecrets
				.helm)

		assertThat(request.helmValuesPath)
			.isEqualTo(ExternalSecretsOperator.HELM_VALUES_PATH)

		assertThat(request.bootstrapWithHelm)
			.isFalse()
	}

	@Test
	void 'uses configured namespace prefix'() {
		config.application.namePrefix = 'tenant-'

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.namespace)
			.isEqualTo('tenant-secrets')
	}

	@Test
	void 'passes additional Helm values unchanged'() {
		config.features.secrets.externalSecrets.helm.values = [replicaCount: 3,
		                                                       webhook     : [port: 9443]]

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.helmConfig.values)
			.isEqualTo([replicaCount: 3,
			            webhook     : [port: 9443]])
	}

	@Test
	void 'passes custom image configuration through Helm request'() {
		Config.SecretsSchema.ESOSchema.ESOHelmSchema helmConfig =
			new Config.SecretsSchema.ESOSchema.ESOHelmSchema([image              : 'localhost:5000/' + 'external-secrets/' + 'external-secrets:v0.6.1',
			                                                  certControllerImage: 'localhost:5000/' + 'external-secrets/' + 'external-secrets-certcontroller:v0.6.1',
			                                                  webhookImage       : 'localhost:5000/' + 'external-secrets/' + 'external-secrets-webhook:v0.6.1'])

		config.features
			.secrets
			.externalSecrets
			.helm = helmConfig

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.helmConfig)
			.isSameAs(helmConfig)
	}

	@Test
	void 'creates image pull secret in secrets namespace'() {
		executeExternalSecretsOperator()

		verify(imagePullSecretCreator)
			.createIfRequired(config,
				'foo-secrets')
	}

	@Test
	void 'prepares external-secrets application content without templates'() {
		executeExternalSecretsOperator()

		assertThat(new File(clusterResourcesRepoDir,
			'apps/external-secrets')).exists()

		assertThat(new File(clusterResourcesRepoDir,
			'apps/external-secrets/templates')).doesNotExist()
	}

	@Test
	void 'delegates deployment to HelmToolDeployer'() {
		executeExternalSecretsOperator()

		verify(helmToolDeployer)
			.deploy(any(HelmToolDeploymentRequest),
				eq(deploymentContext),
				eq(repositoryWorkspace))
	}

	@Test
	void 'publishes external-secrets GitOps resources'() {
		executeExternalSecretsOperator()

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges('Update external-secrets GitOps resources')
	}

	private HelmToolDeploymentRequest executeAndCaptureRequest() {
		executeExternalSecretsOperator()

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer)
			.deploy(captor.capture(),
				eq(deploymentContext),
				eq(repositoryWorkspace))

		return captor.value
	}

	private boolean executeExternalSecretsOperator() {
		createWorkspace()

		deploymentContext = new ContextBuilder(config).build()

		return createExternalSecretsOperator()
			.execute(deploymentContext,
				repositoryWorkspace)
	}

	private ExternalSecretsOperator createExternalSecretsOperator() {
		return new ExternalSecretsOperator(helmToolDeployer,
			imagePullSecretCreator)
	}

	private void createWorkspace() {
		TestGitRepoFactory repositoryFactory =
			new TestGitRepoFactory(config,
				fileSystemUtils) {
				@Override
				GitRepo create(String repositoryTarget,
					GitProvider provider) {
					GitRepo repository =
						super.create(repositoryTarget,
							scmManagerMock)

					clusterResourcesRepoDir = new File(repository
						.absoluteLocalRepoTmpDir)

					return repository
				}
			}

		GitRepo clusterResourcesRepository =
			repositoryFactory.create('argocd/cluster-resources',
				scmManagerMock)

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepository))

		doNothing()
			.when(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges(anyString())
	}
}