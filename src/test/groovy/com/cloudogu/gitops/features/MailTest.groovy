package com.cloudogu.gitops.features

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.ScmManagerMock

import java.nio.file.Files
import java.nio.file.Path
import groovy.yaml.YamlSlurper

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class MailTest {

	Config config = Config.fromMap([application: [namePrefix: "foo-"],
	                                features   : [mail: [mailServer: true

	                                ]]])

	DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
	AirGappedUtils airGappedUtils = mock(AirGappedUtils)
	Path temporaryYamlFile = null
	FileSystemUtils fileSystemUtils = new FileSystemUtils()
	K8sClientForTest k8sClient = new K8sClientForTest(config)
	GitHandler gitHandler = new GitHandlerForTests(config, new ScmManagerMock())

	@Test
	void "is disabled via active flag"() {
		config.features.mail.mailServer = false
		createMail().install()
		assertThat(temporaryYamlFile).isNull()
	}

	@Test
	void 'uses ingress if enabled'() {
		config.features.mail.mailUrl = 'http://mail.local'
		createMail().install()

		def ingressYaml = parseActualYaml()['ingress']
		assertThat(ingressYaml['enabled']).isEqualTo(true)
		assertThat((ingressYaml['hosts'] as List)[0]['host']).isEqualTo('mail.local')
	}

	@Test
	void 'does not use ingress by default'() {
		createMail().install()

		assertThat(parseActualYaml()).doesNotContainKey('ingress')
	}

	@Test
	void 'Password and username can be changed'() {
		String expectedUsername = 'user42'
		String expectedPassword = '12345'
		config.application.username = expectedUsername
		config.application.password = expectedPassword
		createMail().install()

		String fileContents = parseActualYaml()['auth']['fileContents']
		String actualPasswordBcrypted = ((fileContents =~ /^[^:]*:(.*)$/)[0] as List)[1]
		new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)
		assertThat(new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)).isTrue()
			.withFailMessage("Expected password does not match actual hash")
	}

	@Test
	void 'When argocd disabled, mailhog is deployed imperatively via helm'() {
		config.features.argocd.active = false

		createMail().install()

		verify(deploymentStrategy).deployFeature('https://codecentric.github.io/helm-charts',
			'mailhog',
			'mailhog',
			'5.0.1',
			'foo-monitoring',
			'mailhog',
			temporaryYamlFile,
			RepoType.HELM

		)

		assertThat(parseActualYaml()).doesNotContainKey('resources')
		assertThat(parseActualYaml()).doesNotContainKey('imagePullSecrets')
	}

	@Test
	void 'Sets pod resource limits and requests'() {
		config.application.podResources = true

		createMail().install()

		assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
	}

	@Test
	void 'When argoCD enabled, mailhog is deployed natively via argoCD'() {
		config.features.argocd.active = true

		createMail().install()
	}

	@Test
	void 'Allows overriding the image'() {
		config.features.mail.helm.image = 'abc:42'

		createMail().install()
		assertThat(parseActualYaml()['image']['repository']).isEqualTo('abc')
		assertThat(parseActualYaml()['image']['tag']).isEqualTo(42)
	}

	@Test
	void 'Image is optional'() {
		config.features.mail.helm.image = ''

		createMail().install()
		assertThat(parseActualYaml()['image']).isNull()

		config.features.mail.helm.image = null

		createMail().install()
		assertThat(parseActualYaml()['image']).isNull()
	}

	@Test
	void 'custom values are injected correctly'() {
		config.features.mail.helm.values = ["containerPort": ["http": ["port": 9849003 //huge impossible port so it will not match any other configs
		]]]
		createMail().install()
		assertThat(parseActualYaml()['containerPort'] as String).contains('9849003')

	}

	@Test
	void 'helm release is installed in air-gapped mode'() {
		config.application.mirrorRepos = true
		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path SourceChart = rootChartsFolder.resolve('mailhog')
		Files.createDirectories(SourceChart)

		Map ChartYaml = [version: '1.2.3']
		fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

		createMail().install()

		def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
		assertThat(helmConfig.value.chart).isEqualTo('mailhog')
		assertThat(helmConfig.value.repoURL).isEqualTo('https://codecentric.github.io/helm-charts')
		assertThat(helmConfig.value.version).isEqualTo('5.0.1')
		verify(deploymentStrategy).deployFeature('http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b',
			'mailhog', '.', '1.2.3', 'foo-monitoring',
			'mailhog', temporaryYamlFile, RepoType.GIT)
	}

	@Test
	void 'deploys image pull secrets for proxy registry'() {
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'

		createMail().install()

		k8sClient.commandExecutorForTest.assertExecuted('kubectl create secret docker-registry proxy-registry -n foo-monitoring' +
			' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
		assertThat(parseActualYaml()['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
	}

	@Test
	void 'empty security context in openshift'() {
		config.application.openshift = true
		createMail().install()
		assertThat(parseActualYaml()['securityContext']['fsGroup']).isEqualTo(null)
		assertThat(parseActualYaml()['securityContext']['runAsUser']).isEqualTo(null)
		assertThat(parseActualYaml()['securityContext']['runAsGroup']).isEqualTo(null)
	}

	private Mail createMail() {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected

		new Mail(config, new FileSystemUtils() {
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