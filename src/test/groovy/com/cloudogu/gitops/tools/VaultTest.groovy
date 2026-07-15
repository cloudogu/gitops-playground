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
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.utils.FileSystemUtils

import groovy.transform.CompileStatic

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

@CompileStatic
class VaultTest {

	private Config config
	private DeploymentContext deploymentContext
	private RepositoryWorkspace repositoryWorkspace
	private File clusterResourcesRepoDir

	private final FileSystemUtils fileSystemUtils =
		new FileSystemUtils()

	private final HelmToolDeployer helmToolDeployer =
		mock(HelmToolDeployer)

	private final K8sClient k8sClient =
		mock(K8sClient)

	private final ImagePullSecretCreator imagePullSecretCreator =
		mock(ImagePullSecretCreator)

	private ScmManagerProviderMock scmManagerMock

	@BeforeEach
	void setUp() {
		reset(helmToolDeployer,
			k8sClient,
			imagePullSecretCreator)

		config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'),
			features: new Config.FeaturesSchema(secrets: new Config.SecretsSchema(active: true)))

		scmManagerMock = new ScmManagerProviderMock()

		deploymentContext = new ContextBuilder(config).build()
	}

	@Test
	void 'is enabled when secrets feature is active'() {
		assertThat(createVault().isEnabled(deploymentContext)).isTrue()
	}

	@Test
	void 'is disabled when secrets feature is inactive'() {
		config.features.secrets.active = false

		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createVault().isEnabled(context)).isFalse()
	}

	@Test
	void 'creates expected Helm deployment request'() {
		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.toolName)
			.isEqualTo('vault')

		assertThat(request.releaseName)
			.isEqualTo('vault')

		assertThat(request.namespace)
			.isEqualTo('foo-secrets')

		assertThat(request.helmConfig)
			.isSameAs(config.features.secrets.vault.helm)

		assertThat(request.helmValuesPath)
			.isEqualTo(Vault.HELM_VALUES_PATH)

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
		config.features.secrets.vault.helm.values = [server: [replicas: 3]]

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.helmConfig.values)
			.isEqualTo([server: [replicas: 3]])
	}

	@Test
	void 'prepares Vault application content without templates'() {
		executeVault()

		assertThat(new File(clusterResourcesRepoDir,
			'apps/vault')).exists()

		assertThat(new File(clusterResourcesRepoDir,
			'apps/vault/templates')).doesNotExist()
	}

	@Test
	void 'creates image pull secret in Vault namespace'() {
		executeVault()

		verify(imagePullSecretCreator)
			.createIfRequired(config,
				'foo-secrets')
	}

	@Test
	void 'delegates deployment to HelmToolDeployer'() {
		executeVault()

		verify(helmToolDeployer)
			.deploy(any(HelmToolDeploymentRequest),
				eq(deploymentContext),
				eq(repositoryWorkspace))
	}

	@Test
	void 'publishes Vault GitOps resources'() {
		executeVault()

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges('Update vault GitOps resources')
	}

	private HelmToolDeploymentRequest executeAndCaptureRequest() {
		executeVault()

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer)
			.deploy(captor.capture(),
				eq(deploymentContext),
				eq(repositoryWorkspace))

		return captor.value
	}

	private boolean executeVault() {
		createWorkspace()

		deploymentContext = new ContextBuilder(config).build()

		return createVault().execute(deploymentContext,
			repositoryWorkspace)
	}

	private Vault createVault() {
		return new Vault(helmToolDeployer,
			fileSystemUtils,
			k8sClient,
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