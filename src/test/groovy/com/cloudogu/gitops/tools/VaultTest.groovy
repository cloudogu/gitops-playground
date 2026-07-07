package com.cloudogu.gitops.tools

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Files
import java.nio.file.Path
import groovy.yaml.YamlSlurper

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@EnableKubernetesMockClient(crud = true)
@MockitoSettings(strictness = Strictness.LENIENT)
class VaultTest {

	Config config = new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-',),
		features: new Config.FeaturesSchema(secrets: new Config.SecretsSchema(active: true,)))

	CommandExecutorForTest helmCommands = new CommandExecutorForTest()
	FileSystemUtils fileSystemUtils = new FileSystemUtils()
	Deployer deployer = mock(Deployer)
	AirGappedUtils airGappedUtils = mock(AirGappedUtils)
	GitHandler gitHandler = new GitHandlerForTests(config, new ScmManagerProviderMock())
	RepositoryProvisioning repositoryProvisioning = mock(RepositoryProvisioning)

	Path temporaryYamlFile
	File clusterResourcesRepoDir

	K8sClient k8sClient
	KubernetesClient client

	@BeforeEach
	void init() {
		k8sClient = new K8sClient()
		k8sClient.client = client
	}

	@Test
	void 'is disabled via active flag'() {
		config.features.secrets.active = false
		boolean enabled = createVault().install()
		assertFalse(enabled)
	}

	@Test
	void 'prepares vault app content in cluster resources workspace without copying templates'() {
		createVault().install()

		assertThat(new File(clusterResourcesRepoDir, 'apps/vault')).exists()
		assertThat(new File(clusterResourcesRepoDir, 'apps/vault/templates')).doesNotExist()
	}

	@Test
	void 'uses ingress if enabled'() {
		config.features.secrets.vault.url = 'http://vault.local'
		createVault().install()

		def ingressYaml = parseActualYaml()['server']['ingress']
		assertThat(ingressYaml['enabled']).isEqualTo(true)
		assertThat((ingressYaml['hosts'] as List)[0]['host']).isEqualTo('vault.local')
	}

	@Test
	void 'uses ingress if enabled and image set'() {
		config.features.secrets.vault.url = 'http://vault.local'
		// Also set image to make sure ingress and image work at the same time under the server block
		// config.features.secrets.vault.helm.image = 'localhost:5000/hashicorp/vault:1.12.0'
		createVault().install()

		def ingressYaml = parseActualYaml()['server']['ingress']
		assertThat(ingressYaml['enabled']).isEqualTo(true)
	}

	@Test
	void 'does not use ingress by default'() {
		createVault().install()

		assertThat(parseActualYaml()).doesNotContainKey('server')
	}

	@Test
	void 'Dev mode can be enabled via config'() {
		config.features.secrets.vault.mode = 'dev'
		config.application.username = 'abc'
		config.application.password = '123'
		config.features.argocd.active = true

		def vault = createVault()

		vault.install()

		def actualYaml = parseActualYaml()
		assertThat(actualYaml['server']['dev']['enabled']).isEqualTo(true)

		assertThat(actualYaml['server']['dev']['devRootToken']).isNotEqualTo('root')
		assertThat(actualYaml['server']['dev']['devRootToken']).isNotEqualTo(config.application.password)

		List actualPostStart = (List) actualYaml['server']['postStart']
		assertThat(actualPostStart[0]).isEqualTo('/bin/sh')
		assertThat(actualPostStart[1]).isEqualTo('-c')

		assertThat(normalizeShellCommand(actualPostStart[2] as String))
			.isEqualTo('USERNAME=abc PASSWORD=123 ARGOCD=true OIDC_ENABLED=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log')

		List actualVolumes = actualYaml['server']['volumes'] as List
		List actualVolumeMounts = actualYaml['server']['volumeMounts'] as List
		assertThat(actualVolumes[0]['name']).isEqualTo(actualVolumeMounts[0]['name'])
		assertThat(actualVolumes[0]['configMap']['defaultMode']).isEqualTo(Integer.valueOf(0774))

		assertThat(actualVolumeMounts[0]['readOnly']).is(true)
		assertThat(actualPostStart[2] as String).contains(actualVolumeMounts[0]['mountPath'] as String + "/dev-post-start.sh")

		assertThat(actualYaml['server'] as Map).doesNotContainKey('resources')
	}

	@Test
	void 'Dev mode can be enabled via config with argoCD disabled'() {
		config.features.secrets.vault.mode = 'dev'
		config.application.username = 'abc'
		config.application.password = '123'
		createVault().install()

		def actualYaml = parseActualYaml()
		List actualPostStart = (List) actualYaml['server']['postStart']
		assertThat(normalizeShellCommand(actualPostStart[2] as String))
			.isEqualTo('USERNAME=abc PASSWORD=123 ARGOCD=false OIDC_ENABLED=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log')
	}

	@Test
	void 'Dev mode enables OIDC only when configured'() {
		config.features.secrets.vault.mode = 'dev'
		config.features.secrets.vault.url = 'http://vault.localhost'
		config.features.secrets.vault.oidc = new Config.SecretsSchema.VaultSchema.VaultOidcSchema(clientId: 'vault-client',
			clientSecret: 'vault-secret',
			discoveryUrl: 'http://keycloak.local.gd/realms/gop')
		config.application.password = 'admin'

		createVault().install()

		def actualYaml = parseActualYaml()
		List actualPostStart = (List) actualYaml['server']['postStart']
		assertThat(normalizeShellCommand(actualPostStart[2] as String))
			.isEqualTo('USERNAME=admin PASSWORD=admin ARGOCD=false OIDC_ENABLED=true OIDC_CLIENT_ID=vault-client OIDC_CLIENT_SECRET=vault-secret OIDC_DISCOVERY_URL=http://keycloak.local.gd/realms/gop VAULT_EXTERNAL_URL=http://vault.localhost /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log')
	}

	@Test
	void 'Prod mode can be enabled'() {
		config.features.secrets.vault.mode = 'prod'
		createVault().install()

		assertThat(parseActualYaml()).doesNotContainKey('server')
	}

	@Test
	void 'custom image is used'() {
		config.features.secrets.vault.helm.image = 'localhost:5000/hashicorp/vault:1.12.0'
		createVault().install()

		def actualYaml = parseActualYaml()
		assertThat(actualYaml['server']['image']['repository']).isEqualTo('localhost:5000/hashicorp/vault')
		assertThat(actualYaml['server']['image']['tag']).isEqualTo('1.12.0')
	}

	@Test
	void 'helm release is installed'() {
		config.features.secrets.vault.helm = new Config.SecretsSchema.VaultSchema.VaultHelmSchema(chart: 'vault',
			repoURL: 'https://vault-reg',
			version: '42.23.0')
		createVault().install()

		verify(deployer).deployFeature('https://vault-reg',
			'vault',
			'vault',
			'42.23.0',
			'foo-secrets',
			'vault',
			temporaryYamlFile,
			RepoType.HELM,
			false)

		assertThat(parseActualYaml()).doesNotContainKey('global')
	}

	@Test
	void 'helm release is installed in air-gapped mode'() {
		config.application.mirrorRepos = true
		config.features.secrets.vault.helm = new Config.SecretsSchema.VaultSchema.VaultHelmSchema(chart: 'vault',
			repoURL: 'https://vault-reg',
			version: '42.23.0')

		when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

		Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
		config.application.localHelmChartFolder = rootChartsFolder.toString()

		Path sourceChart = rootChartsFolder.resolve('vault')
		Files.createDirectories(sourceChart)

		Map chartYaml = [version: '1.2.3']
		fileSystemUtils.writeYaml(chartYaml, sourceChart.resolve('Chart.yaml').toFile())

		createVault().install()

		def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
		verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
		assertThat(helmConfig.value.chart).isEqualTo('vault')
		assertThat(helmConfig.value.repoURL).isEqualTo('https://vault-reg')
		assertThat(helmConfig.value.version).isEqualTo('42.23.0')
		verify(deployer).deployFeature('http://scmm.scm-manager.svc.cluster.local/scm/repo/a/b',
			'vault',
			'.',
			'1.2.3',
			'foo-secrets',
			'vault',
			temporaryYamlFile,
			RepoType.GIT,
			false)
	}

	@Test
	void 'Sets pod resource limits and requests'() {
		config.application.podResources = true

		createVault().install()

		def actualYaml = parseActualYaml()
		assertThat(actualYaml['server']['resources'] as Map).containsKeys('limits', 'requests')
	}

	@Test
	void 'deploys image pull secrets for proxy registry'() {
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy-url'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-pw'

		createVault().install()

		assertThat(parseActualYaml()['global']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
	}

	private Vault createVault() {
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		FileSystemUtils testFileSystemUtils = new FileSystemUtils() {
			@Override
			Path writeTempFile(Map mapValues) {
				def ret = super.writeTempFile(mapValues)
				temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
				return ret
			}
		}

		ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock()

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

		RepositoryWorkspace repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo)

		when(repositoryProvisioning.provideWorkspace()).thenReturn(repositoryWorkspace)

		return new Vault(new ContextBuilder(config).build(),
			testFileSystemUtils,
			k8sClient,
			deployer,
			airGappedUtils,
			gitHandler,
			repositoryProvisioning)
	}

	private Map parseActualYaml() {
		def ys = new YamlSlurper()
		return ys.parse(temporaryYamlFile) as Map
	}

	private static String normalizeShellCommand(String command) {
		command
			.replaceAll(/\\\s*\r?\n\s*/, ' ')
			.replaceAll(/\s+/, ' ')
			.trim()
	}
}