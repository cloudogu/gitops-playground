package com.cloudogu.gitops.features

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.providers.GitProvider
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

@ExtendWith(MockitoExtension.class)
class IngressTest {

	// setting default config values with ingress active
	Config config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'),
		features: new Config.FeaturesSchema(ingress: new Config.IngressSchema(active: true)))
	Path temporaryYamlFile
	FileSystemUtils fileSystemUtils = new FileSystemUtils()

	K8sClientForTest k8sClient = new K8sClientForTest(config)

	@Mock
	DeploymentStrategy deploymentStrategy
	@Mock
	AirGappedUtils airGappedUtils
	@Mock
	GitHandler gitHandler
	@Mock
	GitProvider gitProvider

	@Test
	void 'Helm release is installed'() {
		createIngress().install()

		/* Assert one default value */
		def actual = parseActualYaml()
		assertThat(actual['deployment']['replicaCount']).isEqualTo(2)

		verify(deploymentStrategy).deployFeature(config.features.ingress.helm.repoURL, 'traefik',
			config.features.ingress.helm.chart, config.features.ingress.helm.version, 'foo-' + config.features.ingress.ingressNamespace,
			'traefik', temporaryYamlFile, RepoType.HELM)
		assertThat(parseActualYaml()['deployment']['metrics']).isNull()
		assertThat(parseActualYaml()['deployment']['networkPolicy']).isNull()
		assertThat(parseActualYaml()).doesNotContainKey('imagePullSecrets')

	}

	@Test
	void 'Sets pod resource limits and requests'() {
		config.application.podResources = true

		createIngress().install()

		assertThat(parseActualYaml()['deployment']['resources'] as Map).containsKeys('limits', 'requests')
	}

	@Test
	void 'When Ingress is not enabled, ingress-helm-values yaml has no content'() {
		config.features.ingress.active = false

		createIngress().install()

		assertThat(temporaryYamlFile).isNull()
	}

	@Test
	void 'additional helm values merged with default values'() {
		config.features.ingress.helm.values = [controller: [replicaCount: 42,
		                                                    span        : '7,5',]]

		createIngress().install()
		def actual = parseActualYaml()

		assertThat(actual['controller']['replicaCount']).isEqualTo(42)
		assertThat(actual['controller']['span']).isEqualTo('7,5')
	}

	@Test
	void 'helm release is installed in air-gapped mode'() {
		when(gitHandler.getResourcesScm()).thenReturn(gitProvider)
		when(gitProvider.repoUrl(any())).thenReturn("http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b")
		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		config.application.mirrorRepos = true

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path SourceChart = rootChartsFolder.resolve('traefik')
		Files.createDirectories(SourceChart)

		Map ChartYaml = [version: '1.2.3']
		fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

		createIngress().install()

		def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
		assertThat(helmConfig.value.chart).isEqualTo('traefik')

		assertThat(helmConfig.value.repoURL).isEqualTo('https://traefik.github.io/charts')
		assertThat(helmConfig.value.version).isEqualTo('39.0.0')
		verify(deploymentStrategy).deployFeature('http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b',
			'traefik', '.', '1.2.3', 'foo-' + config.features.ingress.ingressNamespace,
			'traefik', temporaryYamlFile, RepoType.GIT)
	}

	@Test
	void 'When Monitoring is enabled, metrics are enabled'() {
		config.features.monitoring.active = true
		config.application.namePrefix = "heliosphere"

		createIngress().install()

		def actual = parseActualYaml()

		assertThat(actual['metrics']['enabled']).isEqualTo(true)
		assertThat(actual['metrics']['prometheus']['serviceMonitor']['enabled']).isEqualTo(true)
		assertThat(actual['metrics']['prometheus']['serviceMonitor']['namespace']).isEqualTo("heliospheremonitoring")
	}

	@Test
	void 'Activates network policies'() {
		config.application.netpols = true

		createIngress().install()

		def actual = parseActualYaml()

		assertThat(actual['deployment']['networkPolicy']['enabled']).isEqualTo(true)
	}

	@Test
	void 'deploys image pull secrets for proxy registry'() {
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'

		createIngress().install()

		k8sClient.commandExecutorForTest.assertExecuted('kubectl create secret docker-registry proxy-registry -n foo-ingress' +
			' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')

		assertThat(parseActualYaml()['deployment']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
	}

	@Test
	void 'Allows overriding the image'() {
		config.features.ingress.helm.image = 'localhost/abc:v42'

		createIngress().install()

		def yaml = parseActualYaml()
		assertThat(yaml['image']['repository']).isEqualTo('localhost/abc')
		assertThat(yaml['image']['tag']).isEqualTo('v42')
		assertThat(yaml['image']['digest']).isNull()
	}

	@Test
	void 'get namespace from feature'() {
		assertThat(createIngress().getActiveNamespaceFromFeature()).isEqualTo('foo-' + config.features.ingress.ingressNamespace)
		config.features.ingress.active = false
		assertThat(createIngress().getActiveNamespaceFromFeature()).isEqualTo(null)
	}

	private Ingress createIngress() {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		new Ingress(config, new FileSystemUtils() {
			@Override
			Path writeTempFile(Map mergeMap) {
				def ret = super.writeTempFile(mergeMap)
				temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
				// Path after template invocation
				return ret
			}
		}, deploymentStrategy, k8sClient, airGappedUtils, gitHandler)
	}

	private Map parseActualYaml() {
		def ys = new YamlSlurper()
		return ys.parse(temporaryYamlFile) as Map
	}
}