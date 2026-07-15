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
class CertManagerTest {

	private static final String CHART_VERSION =
		'1.19.4'

	private Config config
	private DeploymentContext deploymentContext
	private RepositoryWorkspace repositoryWorkspace
	private File clusterResourcesRepoDir

	private final FileSystemUtils fileSystemUtils =
		new FileSystemUtils()

	private final HelmToolDeployer helmToolDeployer =
		mock(HelmToolDeployer)

	private final ImagePullSecretCreator imagePullSecretCreator =
		mock(ImagePullSecretCreator)

	private ScmManagerProviderMock scmManagerMock

	@BeforeEach
	void setUp() {
		reset(helmToolDeployer,
			imagePullSecretCreator)

		config = Config.fromMap([application: [namePrefix: ''],
		                         features   : [certManager: [active: true,
		                                                     helm  : [chart  : 'cert-manager',
		                                                              repoURL: 'https://charts.jetstack.io',
		                                                              version: CHART_VERSION]]]])

		scmManagerMock = new ScmManagerProviderMock()
	}

	@Test
	void 'is enabled when cert-manager is active'() {
		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createCertManager().isEnabled(context)).isTrue()
	}

	@Test
	void 'is disabled via active flag'() {
		config.features.certManager.active = false

		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createCertManager().isEnabled(context)).isFalse()
	}

	@Test
	void 'deploys cert-manager with expected Helm request'() {
		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.toolName)
			.isEqualTo('cert-manager')

		assertThat(request.releaseName)
			.isEqualTo('cert-manager')

		assertThat(request.namespace)
			.isEqualTo('cert-manager')

		assertThat(request.helmConfig)
			.isSameAs(config.features.certManager.helm)

		assertThat(request.helmConfig.repoURL)
			.isEqualTo('https://charts.jetstack.io')

		assertThat(request.helmConfig.chart)
			.isEqualTo('cert-manager')

		assertThat(request.helmConfig.version)
			.isEqualTo(CHART_VERSION)

		assertThat(request.helmValuesPath)
			.isEqualTo(CertManager.HELM_VALUES_PATH)

		assertThat(request.bootstrapWithHelm)
			.isFalse()
	}

	@Test
	void 'uses configured namespace prefix'() {
		config.application.namePrefix = 'my-prefix-'

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.namespace)
			.isEqualTo('my-prefix-cert-manager')
	}

	@Test
	void 'passes additional Helm values unchanged'() {
		config.features.certManager.helm.values = [replicaCount: 2,
		                                           webhook     : [timeoutSeconds: 30]]

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.helmConfig.values)
			.isEqualTo([replicaCount: 2,
			            webhook     : [timeoutSeconds: 30]])
	}

	@Test
	void 'creates image pull secret for cert-manager namespace'() {
		executeCertManager()

		verify(imagePullSecretCreator)
			.createIfRequired(config,
				'cert-manager')
	}

	@Test
	void 'prepares cert-manager application content without templates'() {
		executeCertManager()

		assertThat(new File(clusterResourcesRepoDir,
			'apps/cert-manager')).exists()

		assertThat(new File(clusterResourcesRepoDir,
			'apps/cert-manager/templates')).doesNotExist()
	}

	@Test
	void 'replaces all cert-manager templates'() {
		executeCertManager()

		File certManagerDirectory =
			new File(clusterResourcesRepoDir,
				'apps/cert-manager')

		assertThat(certManagerDirectory)
			.exists()

		assertThat(findFilesEndingWith(certManagerDirectory,
			'.ftl')).isEmpty()
	}

	@Test
	void 'delegates deployment to HelmToolDeployer'() {
		executeCertManager()

		verify(helmToolDeployer)
			.deploy(any(HelmToolDeploymentRequest),
				eq(deploymentContext),
				eq(repositoryWorkspace))
	}

	@Test
	void 'publishes generated cert-manager GitOps resources'() {
		executeCertManager()

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges('Update cert-manager GitOps resources')
	}

	private HelmToolDeploymentRequest executeAndCaptureRequest() {
		executeCertManager()

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer)
			.deploy(captor.capture(),
				eq(deploymentContext),
				eq(repositoryWorkspace))

		return captor.value
	}

	private boolean executeCertManager() {
		createWorkspace()

		deploymentContext = new ContextBuilder(config).build()

		return createCertManager().execute(deploymentContext,
			repositoryWorkspace)
	}

	private CertManager createCertManager() {
		return new CertManager(helmToolDeployer,
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

	private static List<File> findFilesEndingWith(File directory,
		String suffix) {
		if (!directory.exists()) {
			return []
		}

		List<File> matchingFiles = []

		File[] files =
			directory.listFiles()

		if (files == null) {
			return matchingFiles
		}

		for (File file : files) {
			if (file.isDirectory()) {
				matchingFiles.addAll(findFilesEndingWith(file,
					suffix))
			} else if (file.name.endsWith(suffix)) {
				matchingFiles.add(file)
			}
		}

		return matchingFiles
	}
}