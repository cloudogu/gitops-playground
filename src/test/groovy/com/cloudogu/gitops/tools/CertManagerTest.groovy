package com.cloudogu.gitops.tools

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest

import java.nio.file.Files
import java.nio.file.Path
import groovy.yaml.YamlSlurper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertManagerTest {

	String chartVersion = '1.19.4'
	Config config = Config.fromMap([features: [certManager: [active: true,
	                                                         helm  : [chart  : 'cert-manager',
	                                                                  repoURL: 'https://charts.jetstack.io',
	                                                                  version: chartVersion,],],],])

	Path temporaryYamlFile
	FileSystemUtils fileSystemUtils = new FileSystemUtils()
	File clusterResourcesRepoDir
	RepositoryWorkspace repositoryWorkspace
	DeploymentContext deploymentContext

	ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock()

	@Mock
	Deployer deploymentStrategy
	@Mock
	AirGappedUtils airGappedUtils
	@Mock
	GitHandler gitHandler
	@Mock
	GitProvider gitProvider

	@Test
	void 'Helm release is installed'() {
		install(createCertManager())

		verify(deploymentStrategy).deployFeature('https://charts.jetstack.io',
			'cert-manager',
			'cert-manager',
			chartVersion,
			'cert-manager',
			'cert-manager',
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace)
	}

	@Test
	void 'prepares cert-manager app content in cluster resources workspace without copying templates'() {
		install(createCertManager())

		assertThat(new File(clusterResourcesRepoDir, 'apps/cert-manager')).exists()
		assertThat(new File(clusterResourcesRepoDir, 'apps/cert-manager/templates')).doesNotExist()
	}

	@Test
	void 'Sets pod resource limits and requests'() {
		config.application.podResources = true

		install(createCertManager())

		assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
		assertThat(parseActualYaml()['cainjector']['resources'] as Map).containsKeys('limits', 'requests')
		assertThat(parseActualYaml()['webhook']['resources'] as Map).containsKeys('limits', 'requests')
	}

	@Test
	void "is disabled via active flag"() {
		config.features.certManager.active = false

		assertFalse(createCertManager().isEnabled(new ContextBuilder(config).build()))
	}

	@Test
	void 'helm release is installed in air-gapped mode'() {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider)
		when(gitProvider.repoUrl(any())).thenReturn('http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b')

		config.application.mirrorRepos = true
		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path sourceChart = rootChartsFolder.resolve('cert-manager')
		Files.createDirectories(sourceChart)

		Map chartYaml = [version: chartVersion]
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve('Chart.yaml').toFile())

		install(createCertManager())

		def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
		assertThat(helmConfig.value.chart).isEqualTo('cert-manager')
		// check existing value, but its not used in deploy.
		assertThat(helmConfig.value.repoURL).isEqualTo('https://charts.jetstack.io')
		assertThat(helmConfig.value.version).isEqualTo(chartVersion)
		// important check: scmmRepoUrl is overridden with our values.
		verify(deploymentStrategy).deployFeature('http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b',
			'cert-manager',
			'.',
			chartVersion,
			'cert-manager',
			'cert-manager',
			temporaryYamlFile,
			RepoType.GIT,
			false,
			deploymentContext,
			repositoryWorkspace)
	}

	@Test
	void 'check images are overriddes'() {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider)
		when(gitProvider.repoUrl(any())).thenReturn('http://test')

		// Prep
		config.application.mirrorRepos = true
		// test values
		config.features.certManager.helm.image = 'this.is.my.registry:30000/this.is.my.repository/myImage:1'
		config.features.certManager.helm.webhookImage = 'this.is.my.registry:30000/this.is.my.repository/myWebhook:2'
		config.features.certManager.helm.cainjectorImage = 'this.is.my.registry:30000/this.is.my.repository/myCainjectorImage:3'
		config.features.certManager.helm.acmeSolverImage = 'this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage:4'
		config.features.certManager.helm.startupAPICheckImage = 'this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage:5'

		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path sourceChart = rootChartsFolder.resolve('cert-manager')
		Files.createDirectories(sourceChart)

		Map chartYaml = [version: chartVersion]
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve('Chart.yaml').toFile())

		install(createCertManager())

		// Cert-Manager
		assertThat(parseActualYaml()['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myImage')
		assertThat(parseActualYaml()['image']['tag'] as String).isEqualTo('1')
		// webhook
		assertThat(parseActualYaml()['webhook']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myWebhook')
		assertThat(parseActualYaml()['webhook']['image']['tag'] as String).isEqualTo('2')
		// cainjector
		assertThat(parseActualYaml()['cainjector']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myCainjectorImage')
		assertThat(parseActualYaml()['cainjector']['image']['tag'] as String).isEqualTo('3')
		// acmesolver
		assertThat(parseActualYaml()['acmesolver']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage')
		assertThat(parseActualYaml()['acmesolver']['image']['tag'] as String).isEqualTo('4')
		// startupapicheck
		assertThat(parseActualYaml()['startupapicheck']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage')
		assertThat(parseActualYaml()['startupapicheck']['image']['tag'] as String).isEqualTo('5')
	}

	private CertManager createCertManager() {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		FileSystemUtils testFileSystemUtils = new FileSystemUtils() {
			@Override
			Path writeTempFile(Map mapValues) {
				def ret = super.writeTempFile(mapValues)
				temporaryYamlFile = Path.of(ret.toString().replace('.ftl', ''))
				return ret
			}
		}

		TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, testFileSystemUtils) {
			@Override
			GitRepo create(String repoTarget, GitProvider provider) {
				def repo = super.create(repoTarget, provider)
				clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir())

				return repo
			}
		}

		GitRepo clusterResourcesRepo = repoProvider.create('argocd/cluster-resources',
			scmManagerMock)

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo))
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString())

		return new CertManager(testFileSystemUtils,
			deploymentStrategy,
			new K8sClientForTest(),
			airGappedUtils,
			gitHandler)
	}

	private boolean install(CertManager certManager) {
		deploymentContext = new ContextBuilder(config).build()
		return certManager.execute(deploymentContext, repositoryWorkspace)
	}

	private Map parseActualYaml() {
		def ys = new YamlSlurper()
		return ys.parse(temporaryYamlFile) as Map
	}
}