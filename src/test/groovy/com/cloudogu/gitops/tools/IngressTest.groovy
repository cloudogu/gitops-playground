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
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Files
import java.nio.file.Path
import groovy.transform.CompileStatic
import groovy.yaml.YamlSlurper

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.BeforeEach
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
class IngressTest {

	// setting default config values with ingress active
	Config config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'),
		features: new Config.FeaturesSchema(ingress: new Config.IngressSchema(active: true)))

	Path temporaryYamlFile
	FileSystemUtils fileSystemUtils = new FileSystemUtils()
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

	K8sClient k8sClient
	KubernetesClient client

	@BeforeEach
	void init() {
		k8sClient = new K8sClient()
		k8sClient.client = client
	}

	@Test
	void 'Helm release is installed'() {
		install(createIngress())

		/* Assert one default value */
		def actual = parseActualYaml()
		assertThat(actual['deployment']['replicaCount']).isEqualTo(2)

		verify(deployer).deployFeature(config.features.ingress.helm.repoURL,
			'traefik',
			config.features.ingress.helm.chart,
			config.features.ingress.helm.version,
			'foo-' + config.features.ingress.ingressNamespace,
			'traefik',
			temporaryYamlFile,
			RepoType.HELM,
			false,
			deploymentContext,
			repositoryWorkspace)

		assertThat(parseActualYaml()['deployment']['metrics']).isNull()
		assertThat(parseActualYaml()['deployment']['networkPolicy']).isNull()
		assertThat(parseActualYaml()).doesNotContainKey('imagePullSecrets')
	}

	@Test
	void 'prepares traefik app content in cluster resources workspace without copying templates'() {
		install(createIngress())

		assertThat(new File(clusterResourcesRepoDir, 'apps/traefik')).exists()
		assertThat(new File(clusterResourcesRepoDir, 'apps/traefik/templates')).doesNotExist()
	}

	@Test
	void 'Sets pod resource limits and requests'() {
		config.application.podResources = true

		install(createIngress())

		assertThat(parseActualYaml()['deployment']['resources'] as Map).containsKeys('limits', 'requests')
	}

	@Test
	void 'When Ingress is not enabled, ingress-helm-values yaml has no content'() {
		config.features.ingress.active = false

		assertFalse(createIngress().isEnabled(new ContextBuilder(config).build()))
	}

	@Test
	void 'additional helm values merged with default values'() {
		config.features.ingress.helm.values = [controller: [replicaCount: 42,
		                                                    span        : '7,5',]]

		install(createIngress())
		def actual = parseActualYaml()

		assertThat(actual['controller']['replicaCount']).isEqualTo(42)
		assertThat(actual['controller']['span']).isEqualTo('7,5')
	}

	@Test
	void 'helm release is installed in air-gapped mode'() {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider)
		when(gitProvider.repoUrl(any())).thenReturn('http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b')
		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		config.application.mirrorRepos = true

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path sourceChart = rootChartsFolder.resolve('traefik')
		Files.createDirectories(sourceChart)

		Map chartYaml = [version: '1.2.3']
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve('Chart.yaml').toFile())

		install(createIngress())

		def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
		assertThat(helmConfig.value.chart).isEqualTo('traefik')

		assertThat(helmConfig.value.repoURL).isEqualTo('https://traefik.github.io/charts')
		assertThat(helmConfig.value.version).isEqualTo('39.0.0')

		verify(deployer).deployFeature('http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b',
			'traefik',
			'.',
			'1.2.3',
			'foo-' + config.features.ingress.ingressNamespace,
			'traefik',
			temporaryYamlFile,
			RepoType.GIT,
			false,
			deploymentContext,
			repositoryWorkspace)
	}

	@Test
	void 'When Monitoring is enabled, metrics are enabled'() {
		config.features.monitoring.active = true
		config.application.namePrefix = 'heliosphere'

		install(createIngress())

		def actual = parseActualYaml()

		assertThat(actual['metrics']['enabled']).isEqualTo(true)
		assertThat(actual['metrics']['prometheus']['serviceMonitor']['enabled']).isEqualTo(true)
		assertThat(actual['metrics']['prometheus']['serviceMonitor']['namespace']).isEqualTo('heliospheremonitoring')
	}

	@Test
	void 'Activates network policies'() {
		config.application.netpols = true

		install(createIngress())

		def actual = parseActualYaml()

		assertThat(actual['deployment']['networkPolicy']['enabled']).isEqualTo(true)
	}

	@Test
	void 'deploys image pull secrets for proxy registry'() {
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'

		install(createIngress())

		assertThat(parseActualYaml()['deployment']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
	}

	@Test
	void 'Allows overriding the image'() {
		config.features.ingress.helm.image = 'localhost/abc:v42'

		install(createIngress())

		def yaml = parseActualYaml()
		assertThat(yaml['image']['repository']).isEqualTo('localhost/abc')
		assertThat(yaml['image']['tag']).isEqualTo('v42')
		assertThat(yaml['image']['digest']).isNull()
	}

	@Test
	void 'get namespace from feature'() {
		assertThat(createIngress().getActiveNamespace(new ContextBuilder(config).build())).isEqualTo('foo-' + config.features.ingress.ingressNamespace)

		config.features.ingress.active = false

		assertThat(createIngress().getActiveNamespace(new ContextBuilder(config).build())).isEqualTo(null)
	}

	private Ingress createIngress() {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		FileSystemUtils testFileSystemUtils = new FileSystemUtils() {
			@Override
			Path writeTempFile(Map mergeMap) {
				def ret = super.writeTempFile(mergeMap)
				temporaryYamlFile = Path.of(ret.toString().replace('.ftl', ''))
				// Path after template invocation
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

		return new Ingress(testFileSystemUtils,
			deployer,
			k8sClient,
			airGappedUtils,
			gitHandler,
			imagePullSecretCreator)
	}

	private boolean install(Ingress ingress) {
		deploymentContext = new ContextBuilder(config).build()
		return ingress.execute(deploymentContext, repositoryWorkspace)
	}

	private Map parseActualYaml() {
		def ys = new YamlSlurper()
		return ys.parse(temporaryYamlFile) as Map
	}
}