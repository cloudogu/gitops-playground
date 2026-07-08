package com.cloudogu.gitops.tools.core

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy
import com.cloudogu.gitops.infrastructure.deployment.HelmStrategy
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.PluginApi
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApi
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.tools.core.scmmanager.ScmManagerSetup

import java.nio.file.Path
import groovy.yaml.YamlSlurper

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import retrofit2.Call
import retrofit2.Response

class ScmManagerSetupTest {

	ScmManagerProvider scmManager = mock(ScmManagerProvider.class)

	Deployer deployer = mock(Deployer.class)
	HelmStrategy helmStrategy = mock(HelmStrategy.class)

	GitProvider tenantProvider = mock(GitProvider)
	GitProvider centralProvider = mock(GitProvider)

	GitRepo clusterResourcesRepo = mock(GitRepo)
	GitRepo tenantBootstrapRepo = mock(GitRepo)

	ScmManagerApiClient apiClient = mock(ScmManagerApiClient.class)
	PluginApi pluginApi = mock(PluginApi.class)
	ScmManagerApi generalApi = mock(ScmManagerApi.class)

	Config config = Config.fromMap([application: [namePrefix: 'test',
	                                              insecure  : true],
	                                jenkins    : [active   : false,
	                                              urlForScm: 'http://jenkins.jenkins.svc.cluster.local'],
	                                scm        : [scmManager: [internal      : true,
	                                                           url           : '',
	                                                           namespace     : 'scm-manager',
	                                                           username      : 'admin',
	                                                           password      : 'admin',
	                                                           helm          : [chart  : 'scm-manager',
	                                                                            repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
	                                                                            version: '3.11.2',
	                                                                            values : [:]],
	                                                           urlForJenkins : 'http://scmm.scm-manager.svc.cluster.local/scm',
	                                                           ingress       : 'scmm.master.localhost',
	                                                           skipRestart   : false,
	                                                           skipPlugins   : false,
	                                                           gitOpsUsername: 'gitops',
	                                                           credentials   : [username: 'admin',
	                                                                            password: 'admin']]]])

	@BeforeEach
	void setUp() {
		clusterResourcesRepo.gitProvider = centralProvider
		tenantBootstrapRepo.gitProvider = tenantProvider

		doReturn('argocd/cluster-resources')
			.when(clusterResourcesRepo)
			.getRepoTarget()

		doReturn('argocd/cluster-resources')
			.when(tenantBootstrapRepo)
			.getRepoTarget()

		doReturn(createTempDir('cluster-resources'))
			.when(clusterResourcesRepo)
			.getAbsoluteLocalRepoTmpDir()

		doReturn(createTempDir('tenant-bootstrap'))
			.when(tenantBootstrapRepo)
			.getAbsoluteLocalRepoTmpDir()
	}

	@Test
	void 'Helm chart is installed correctly'() {
		when(scmManager.getConfig()).thenReturn(config)
		when(scmManager.getScmmConfig()).thenReturn(config.scm.scmManager)
		when(deployer.getHelmStrategy()).thenReturn(helmStrategy)
		config.scm.scmManager.scmmImage = 'localhost:5000/proxy/scm-manager:custom'

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo))

		// Usually ApplicationConfigurator modifies the namePrefix and sets it to "namePrefix-"
		config.application.namePrefix = "${config.application.namePrefix}-"
		scmManagerSetup.setupHelm()

		ArgumentCaptor<Path> valuesPathCaptor = ArgumentCaptor.forClass(Path.class)
		verify(helmStrategy).deployFeature(eq('https://packages.scm-manager.org/repository/helm-v2-releases/'),
			eq('scm-manager'),
			eq('scm-manager'),
			eq('3.11.2'),
			eq('scm-manager'),
			eq('test-scmm'),
			valuesPathCaptor.capture(),
			eq(DeploymentStrategy.RepoType.HELM))

		Map values = new YamlSlurper().parse(valuesPathCaptor.value) as Map
		assertThat((values.image as Map).repository).isEqualTo('localhost:5000/proxy/scm-manager')
		assertThat((values.image as Map).tag).isEqualTo('custom')
	}

	@Test
	void 'Helm values contain cert manager ingress configuration'() {
		when(scmManager.getConfig()).thenReturn(config)
		when(scmManager.getScmmConfig()).thenReturn(config.scm.scmManager)
		when(deployer.getHelmStrategy()).thenReturn(helmStrategy)
		config.features.certManager.active = true
		config.features.certManager.issuer = 'cluster-selfsigned'

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo))

		// Usually ApplicationConfigurator modifies the namePrefix and sets it to "namePrefix-"
		config.application.namePrefix = "${config.application.namePrefix}-"
		scmManagerSetup.setupHelm()

		ArgumentCaptor<Path> valuesPathCaptor = ArgumentCaptor.forClass(Path.class)
		verify(helmStrategy).deployFeature(eq('https://packages.scm-manager.org/repository/helm-v2-releases/'),
			eq('scm-manager'),
			eq('scm-manager'),
			eq('3.11.2'),
			eq('scm-manager'),
			eq('test-scmm'),
			valuesPathCaptor.capture(),
			eq(DeploymentStrategy.RepoType.HELM))

		Map values = new YamlSlurper().parse(valuesPathCaptor.value) as Map
		Map ingress = values.ingress as Map
		List tls = ingress.tls as List
		Map tlsEntry = tls[0] as Map

		assertThat((ingress.annotations as Map)['cert-manager.io/cluster-issuer']).isEqualTo('cluster-selfsigned')
		assertThat(tlsEntry.secretName).isEqualTo('scm-manager-tls')
		assertThat(tlsEntry.hosts as List).containsExactly('scmm.master.localhost')
	}

	@Test
	void 'ScmManager plugins are installed correctly'() {
		when(scmManager.getConfig()).thenReturn(config)
		when(scmManager.getScmmConfig()).thenReturn(config.scm.scmManager)
		when(scmManager.getApiClient()).thenReturn(apiClient)

		Call<Void> apiCall = mock(Call.class)

		when(pluginApi.install(any(String), any(Boolean))).thenReturn(apiCall)
		when(generalApi.checkScmmAvailable()).thenReturn(apiCall)

		when(apiClient.pluginApi()).thenReturn(pluginApi)
		when(apiClient.generalApi()).thenReturn(generalApi)

		when(apiCall.execute()).thenReturn(Response.success(null))

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			new RepositoryWorkspace(clusterResourcesRepo))

		invokePrivateInstallScmmPlugins(scmManagerSetup)

		verify(pluginApi, times(10)).install(any(String), any(Boolean))
	}

	@Test
	void 'prepareBootstrapRepositoriesAfterScmManagerDeployment initializes cluster resources repository'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo)

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace)

		scmManagerSetup.prepareBootstrapRepositoriesAfterScmManagerDeployment()

		verify(centralProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for basic cluster-resources',
			true)

		verify(clusterResourcesRepo).initLocalRepoIfNeeded()
		verify(clusterResourcesRepo).checkoutRemoteMainIfLocalMainMissing()
		verify(clusterResourcesRepo, never()).commitAndPush(anyString())
	}

	@Test
	void 'pushBootstrapRepositoriesAfterScmManagerDeployment pushes cluster resources repository'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo)

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace)

		scmManagerSetup.pushBootstrapRepositoriesAfterScmManagerDeployment()

		verify(clusterResourcesRepo).commitAndPush('Bootstrap cluster-resources repository after SCM-Manager deployment')
	}

	@Test
	void 'prepareBootstrapRepositoriesAfterScmManagerDeployment initializes both repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo,
			tenantBootstrapRepo)

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace)

		scmManagerSetup.prepareBootstrapRepositoriesAfterScmManagerDeployment()

		verify(centralProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for basic cluster-resources',
			true)
		verify(tenantProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for tenant bootstrap resources',
			true)

		verify(clusterResourcesRepo).initLocalRepoIfNeeded()
		verify(clusterResourcesRepo).checkoutRemoteMainIfLocalMainMissing()
		verify(clusterResourcesRepo, never()).commitAndPush(anyString())

		verify(tenantBootstrapRepo).initLocalRepoIfNeeded()
		verify(tenantBootstrapRepo).checkoutRemoteMainIfLocalMainMissing()
		verify(tenantBootstrapRepo, never()).commitAndPush(anyString())
	}

	@Test
	void 'pushBootstrapRepositoriesAfterScmManagerDeployment pushes both repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo,
			tenantBootstrapRepo)

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager,
			deployer,
			new ContextBuilder(config).build(),
			workspace)

		scmManagerSetup.pushBootstrapRepositoriesAfterScmManagerDeployment()

		verify(clusterResourcesRepo).commitAndPush('Bootstrap cluster-resources repository after SCM-Manager deployment')
		verify(tenantBootstrapRepo).commitAndPush('Bootstrap tenant repository after SCM-Manager deployment')
	}

	private static void invokePrivateInstallScmmPlugins(ScmManagerSetup scmManagerSetup) {
		def method = ScmManagerSetup.getDeclaredMethod('installScmmPlugins')
		method.accessible = true
		method.invoke(scmManagerSetup)
	}

	private static String createTempDir(String prefix) {
		return File.createTempDir(prefix, '').canonicalPath
	}
}
