package com.cloudogu.gitops.tools.core

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy
import com.cloudogu.gitops.infrastructure.deployment.HelmStrategy
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.PluginApi
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApi
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.tools.core.scmmanager.ScmManagerSetup

import java.nio.file.Path
import groovy.yaml.YamlSlurper

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import retrofit2.Call
import retrofit2.Response

class ScmManagerSetupTest {

	ScmManagerProvider scmManager = mock(ScmManagerProvider.class)

	Deployer deployer = mock(Deployer.class)
	HelmStrategy helmStrategy = mock(HelmStrategy.class)

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

	@Test
	void 'Helm chart is installed correctly'() {
		when(scmManager.getConfig()).thenReturn(config)
		when(scmManager.getScmmConfig()).thenReturn(config.scm.scmManager)
		when(deployer.getHelmStrategy()).thenReturn(helmStrategy)
		config.scm.scmManager.scmmImage = 'localhost:5000/proxy/scm-manager:custom'

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager, deployer)

		scmManagerSetup.setupHelm()

		ArgumentCaptor<Path> valuesPathCaptor = ArgumentCaptor.forClass(Path.class)
		verify(helmStrategy).deployFeature(eq('https://packages.scm-manager.org/repository/helm-v2-releases/'),
			eq('scm-manager'),
			eq('scm-manager'),
			eq('3.11.2'),
			eq('scm-manager'),
			eq('scmm'),
			valuesPathCaptor.capture(),
			eq(DeploymentStrategy.RepoType.HELM))

		Map values = new YamlSlurper().parse(valuesPathCaptor.value) as Map
		assertThat((values.image as Map).repository).isEqualTo('localhost:5000/proxy/scm-manager')
		assertThat((values.image as Map).tag).isEqualTo('custom')
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

		ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager, deployer)

		invokePrivateInstallScmmPlugins(scmManagerSetup)

		verify(pluginApi, times(10)).install(any(String), any(Boolean))
	}

	private static void invokePrivateInstallScmmPlugins(ScmManagerSetup scmManagerSetup) {
		def method = ScmManagerSetup.getDeclaredMethod('installScmmPlugins')
		method.accessible = true
		method.invoke(scmManagerSetup)
	}
}