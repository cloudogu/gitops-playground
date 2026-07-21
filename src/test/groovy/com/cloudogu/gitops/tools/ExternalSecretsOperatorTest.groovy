package com.cloudogu.gitops.tools

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.mockito.ArgumentMatchers.*
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
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Files
import java.nio.file.Path
import groovy.transform.CompileStatic
import groovy.yaml.YamlSlurper

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@CompileStatic
@ExtendWith(MockitoExtension)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnableKubernetesMockClient(crud = true)
class ExternalSecretsOperatorTest {

	Config config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'),
		registry: new Config.RegistrySchema(),
		features: new Config.FeaturesSchema(secrets: new Config.SecretsSchema(active: true)))

	CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
	FileSystemUtils fileSystemUtils = new FileSystemUtils()
	Path temporaryYamlFile
	File clusterResourcesRepoDir
	RepositoryWorkspace repositoryWorkspace
	DeploymentContext deploymentContext

	ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock()

	@Mock
	Deployer deployer
	@Mock
	AirGappedUtils airGappedUtils
	@Mock
	GitHandler gitHandler
	@Mock
	GitProvider gitProvider
	@Mock
	ImagePullSecretCreator imagePullSecretCreator

	KubernetesClient client

	@Test
	void "is disabled via active flag"() {
		config.features.secrets.active = false

		assertFalse(createExternalSecretsOperator().isEnabled(new ContextBuilder(config).build()))
	}

	@Test
	void 'helm release is installed'() {
		install(createExternalSecretsOperator())

		verify(deployer).deployFeature('https://charts.external-secrets.io',
			'external-secrets',
			'external-secrets',
			'0.9.16',
			'foo-secrets',
			'external-secrets',
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace)

		assertThat(parseActualYaml()).doesNotContainKeys('resources')
		assertThat(parseActualYaml()).doesNotContainKey('imagePullSecrets')
		assertThat(parseActualYaml()).doesNotContainKey('certController')
		assertThat(parseActualYaml()).doesNotContainKey('webhook')

		assertThat(parseActualYaml()['installCRDs']).isNull()
	}

	@Test
	void 'prepares external-secrets app content in cluster resources workspace without copying templates'() {
		install(createExternalSecretsOperator())

		assertThat(new File(clusterResourcesRepoDir, 'apps/external-secrets')).exists()
		assertThat(new File(clusterResourcesRepoDir, 'apps/external-secrets/templates')).doesNotExist()
	}

	@Test
	void 'Skips CRDs'() {
		config.application.skipCrds = true

		install(createExternalSecretsOperator())

		assertThat(parseActualYaml()['installCRDs']).isEqualTo(false)
	}

	@Test
	void 'helm release is installed with custom images'() {
		config.features.secrets.externalSecrets.helm = new Config.SecretsSchema.ESOSchema.ESOHelmSchema([image              : 'localhost:5000/external-secrets/external-secrets:v0.6.1',
		                                                                                                 certControllerImage: 'localhost:5000/external-secrets/external-secrets-certcontroller:v0.6.1',
		                                                                                                 webhookImage       : 'localhost:5000/external-secrets/external-secrets-webhook:v0.6.1'])
		install(createExternalSecretsOperator())

		def valuesYaml = parseActualYaml()
		assertThat(valuesYaml['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets')
		assertThat(valuesYaml['image']['tag']).isEqualTo('v0.6.1')

		assertThat(valuesYaml['certController']['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets-certcontroller')
		assertThat(valuesYaml['certController']['image']['tag']).isEqualTo('v0.6.1')

		assertThat(valuesYaml['webhook']['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets-webhook')
		assertThat(valuesYaml['webhook']['image']['tag']).isEqualTo('v0.6.1')
	}

	@Test
	void 'Sets pod resource limits and requests'() {
		config.application.podResources = true

		install(createExternalSecretsOperator())

		assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
		assertThat(parseActualYaml()['webhook']['resources'] as Map).containsKeys('limits', 'requests')
		assertThat(parseActualYaml()['certController']['resources'] as Map).containsKeys('limits', 'requests')
	}

	@Test
	void 'helm release is installed in air-gapped mode'() {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider)
		when(gitProvider.repoUrl(any())).thenReturn('http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b')
		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		config.application.mirrorRepos = true

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path sourceChart = rootChartsFolder.resolve('external-secrets')
		Files.createDirectories(sourceChart)

		Map chartYaml = [version: '1.2.3']
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve('Chart.yaml').toFile())

		install(createExternalSecretsOperator())

		def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
		assertThat(helmConfig.value.chart).isEqualTo('external-secrets')
		assertThat(helmConfig.value.repoURL).isEqualTo('https://charts.external-secrets.io')
		assertThat(helmConfig.value.version).isEqualTo('0.9.16')

		verify(deployer).deployFeature(eq('http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b'),
			eq('external-secrets'),
			eq('.'),
			eq('1.2.3'),
			eq('foo-secrets'),
			eq('external-secrets'),
			eq(temporaryYamlFile),
			eq(RepoType.GIT),
			eq(false),
			eq(deploymentContext),
			eq(repositoryWorkspace))
	}

	@Test
	void 'deploys image pull secrets for proxy registry'() {
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'
		config.features.secrets.externalSecrets.helm = new Config.SecretsSchema.ESOSchema.ESOHelmSchema([certControllerImage: 'some:thing',
		                                                                                                 webhookImage       : 'some:thing'])

		install(createExternalSecretsOperator())

		assertThat(parseActualYaml()['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
		assertThat(parseActualYaml()['certController']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
		assertThat(parseActualYaml()['webhook']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
	}

	private ExternalSecretsOperator createExternalSecretsOperator() {
		FileSystemUtils fileSystemUtils = new FileSystemUtils() {
			@Override
			Path writeTempFile(Map mergeMap) {
				def ret = super.writeTempFile(mergeMap)
				temporaryYamlFile = Path.of(ret.toString().replace('.ftl', ''))
				// Path after template invocation
				return ret
			}
		}

		TestGitRepoFactory repoFactory = new TestGitRepoFactory(config, new FileSystemUtils()) {
			@Override
			GitRepo create(String repoTarget, GitProvider scm) {
				GitRepo repo = super.create(repoTarget, scm)
				clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir())
				return repo
			}
		}

		GitRepo clusterResourcesRepo = repoFactory.create('argocd/cluster-resources',
			scmManagerMock)

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo))
		doNothing().when(repositoryWorkspace).commitAndPushClusterResourcesChanges(anyString())

		return new ExternalSecretsOperator(fileSystemUtils,
			deployer,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator)
	}

	private boolean install(ExternalSecretsOperator operator) {
		deploymentContext = new ContextBuilder(config).build()
		return operator.execute(deploymentContext, repositoryWorkspace)
	}

	private Map parseActualYaml() {
		def ys = new YamlSlurper()
		return ys.parse(temporaryYamlFile) as Map
	}
}