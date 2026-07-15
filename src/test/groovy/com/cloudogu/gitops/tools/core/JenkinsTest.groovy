package com.cloudogu.gitops.tools.core

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.infrastructure.jenkins.JobManager
import com.cloudogu.gitops.infrastructure.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.infrastructure.jenkins.UserManager
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils

import groovy.transform.CompileStatic

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

@CompileStatic
class JenkinsTest {

	Config config = new Config(scm: new ScmTenantSchema(scmManager: new ScmTenantSchema.ScmManagerTenantConfig(urlForJenkins: 'testUrlJenkins')),
		jenkins: new Config.JenkinsSchema(active: true))

	String expectedNodeName = 'something'

	CommandExecutorForTest commandExecutor =
		new CommandExecutorForTest()

	FileSystemUtils fileSystemUtils =
		new FileSystemUtils()

	GlobalPropertyManager globalPropertyManager =
		mock(GlobalPropertyManager)

	JobManager jobManager =
		mock(JobManager)

	UserManager userManager =
		mock(UserManager)

	PrometheusConfigurator prometheusConfigurator =
		mock(PrometheusConfigurator)

	HelmToolDeployer helmToolDeployer =
		mock(HelmToolDeployer)

	NetworkingUtils networkingUtils =
		mock(NetworkingUtils)

	K8sClient k8sClient =
		mock(K8sClient)

	ImagePullSecretCreator imagePullSecretCreator =
		mock(ImagePullSecretCreator)

	ScmManagerProviderMock scmManagerMock =
		new ScmManagerProviderMock()

	GitHandler gitHandler =
		new GitHandlerForTests(scmManagerMock)

	RepositoryWorkspace repositoryWorkspace
	DeploymentContext deploymentContext
	File localTempDir

	@BeforeEach
	void setup() {
		when(k8sClient.waitForNode())
			.thenReturn("node/${expectedNodeName}".toString())

		when(k8sClient.run(anyString(),
			anyString(),
			anyString(),
			anyMap(),
			any(String[].class))).thenReturn('')

		when(networkingUtils.createUrl(anyString(),
			anyString(),
			anyString())).thenCallRealMethod()

		when(networkingUtils.createUrl(anyString(),
			anyString())).thenCallRealMethod()
	}

	@Test
	void 'creates expected Helm deployment request for internal Jenkins'() {
		config.jenkins.helm.chart = 'jen-chart'
		config.jenkins.helm.repoURL = 'https://jen-repo'
		config.jenkins.helm.version = '4.8.1'

		when(k8sClient.run(anyString(),
			anyString(),
			anyString(),
			anyMap(),
			any(String[].class))).thenReturn('''
root:x:0:
daemon:x:1:
docker:x:42:me
me:x:1000:
''')

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.toolName)
			.isEqualTo('jenkins')

		assertThat(request.releaseName)
			.isEqualTo('jenkins')

		assertThat(request.namespace)
			.isEqualTo('jenkins')

		assertThat(request.helmConfig.repoURL)
			.isEqualTo('https://jen-repo')

		assertThat(request.helmConfig.chart)
			.isEqualTo('jen-chart')

		assertThat(request.helmConfig.version)
			.isEqualTo('4.8.1')

		assertThat(request.helmValuesPath)
			.isEqualTo(Jenkins.HELM_VALUES_PATH)

		assertThat(request.bootstrapWithHelm)
			.isTrue()

		assertThat(request.templateData['dockerGid'])
			.isEqualTo('42')

		assertThat(request.templateData['jenkinsBootPlugins'])
			.isEqualTo([])
	}

	@Test
	void 'uses configured namespace prefix'() {
		config.application.namePrefix = 'tenant-'

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.namespace)
			.isEqualTo('tenant-jenkins')
	}

	@Test
	void 'returns active namespace only for internal Jenkins'() {
		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(createJenkins()
			.getActiveNamespace(context)).isEqualTo('jenkins')

		config.jenkins.internal = false

		DeploymentContext externalContext =
			new ContextBuilder(config).build()

		assertThat(createJenkins()
			.getActiveNamespace(externalContext)).isNull()
	}

	@Test
	void 'prepares Jenkins app content in cluster resources workspace'() {
		install(createJenkins())

		assertThat(new File(localTempDir, 'apps/jenkins')).exists()

		assertThat(new File(localTempDir,
			'apps/jenkins/templates')).doesNotExist()
	}

	@Test
	void 'prepares Kubernetes resources for internal Jenkins'() {
		config.jenkins.username = 'jenusr'
		config.jenkins.password = 'jenpw'

		install(createJenkins())

		verify(imagePullSecretCreator)
			.createIfRequired(config,
				'jenkins')

		verify(k8sClient)
			.createNamespace('jenkins')

		verify(k8sClient)
			.labelRemove('node',
				'--all',
				'',
				'node')

		verify(k8sClient)
			.label('node',
				expectedNodeName,
				new Tuple2('node',
					'jenkins'))

		verify(k8sClient)
			.createSecret('generic',
				'jenkins-credentials',
				'jenkins',
				new Tuple2('jenkins-admin-user',
					'jenusr'),
				new Tuple2('jenkins-admin-password',
					'jenpw'))
	}

	@Test
	void 'uses Docker group ID in Helm template data'() {
		when(k8sClient.run(anyString(),
			anyString(),
			anyString(),
			anyMap(),
			any(String[].class))).thenReturn('''
root:x:0:
daemon:x:1:
docker:x:42:me
me:x:1000:
''')

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.templateData['dockerGid'])
			.isEqualTo('42')

		ArgumentCaptor<String> nameCaptor =
			ArgumentCaptor.forClass(String)

		ArgumentCaptor<Map> overridesCaptor =
			ArgumentCaptor.forClass(Map)

		verify(k8sClient).run(nameCaptor.capture(),
			anyString(),
			eq('jenkins'),
			overridesCaptor.capture(),
			any(String[].class))

		assertThat(nameCaptor.value)
			.startsWith('tmp-docker-gid-grepper-')

		List containers =
			overridesCaptor
				.value['spec']['containers']
				as List

		assertThat(containers[0]['image'])
			.isEqualTo(config.jenkins.internalBashImage)
	}

	@Test
	void 'uses empty Docker group ID when Docker group is missing'() {
		when(k8sClient.run(anyString(),
			anyString(),
			anyString(),
			anyMap(),
			any(String[].class))).thenReturn('''
root:x:0:
daemon:x:1:
me:x:1000:
''')

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.templateData['dockerGid'])
			.isEqualTo('')
	}

	@Test
	void 'adds required OIDC boot plugins when OIDC is configured'() {
		config.jenkins.oidc = '''
jenkins:
  securityRealm:
    oic:
      clientId: "jenkins"
'''

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		List<String> bootPlugins =
			request.templateData['jenkinsBootPlugins']
				as List<String>

		assertThat(bootPlugins.collect {
			it.split(':')[0]
		}).containsExactly('oic-auth',
			'json-path-api')
	}

	@Test
	void 'does not deploy or publish external Jenkins'() {
		config.jenkins.internal = false
		config.registry.createImagePullSecrets = true

		install(createJenkins())

		verifyNoInteractions(helmToolDeployer,
			imagePullSecretCreator)

		verify(repositoryWorkspace, never())
			.commitAndPushClusterResourcesChanges(anyString())

		verify(k8sClient, never())
			.createNamespace(anyString())

		verify(k8sClient, never())
			.createSecret(anyString(),
				anyString(),
				anyString(),
				any())
	}

	@Test
	void 'still runs setup script for external Jenkins'() {
		config.jenkins.internal = false
		config.jenkins.url = 'https://external-jenkins.example.org'

		install(createJenkins())

		assertThat(commandExecutor.actualCommands)
			.hasSize(1)

		assertThat(commandExecutor.actualCommands[0])
			.endsWith('/scripts/jenkins/init-jenkins.sh')

		Map<String, String> environment =
			getEnvAsMap()

		assertThat(environment['INTERNAL_JENKINS']).isEqualTo('false')

		assertThat(environment['JENKINS_URL'])
			.isEqualTo('https://external-jenkins.example.org')
	}

	@Test
	void 'passes configured Helm values unchanged'() {
		config.jenkins.helm.values = [controller: [nodePort: 42]]

		HelmToolDeploymentRequest request =
			executeAndCaptureRequest()

		assertThat(request.helmConfig.values)
			.isEqualTo([controller: [nodePort: 42]])
	}

	@Test
	void 'publishes Jenkins GitOps resources'() {
		install(createJenkins())

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges('Update jenkins GitOps resources')
	}

	@Test
	void 'maps configuration to Jenkins setup script environment'() {
		config.application.trace = true
		config.features.argocd.active = true
		config.scm.scmManager.url = 'http://scmm.scm-manager.svc.cluster.local/scm'

		config.scm.scmManager.username = 'scmm-usr'

		config.scm.scmManager.password = 'scmm-pw'

		config.application.namePrefix = 'my-prefix-'

		config.application.namePrefixForEnvVars = 'MY_PREFIX_'

		config.registry.url = 'reg-url'
		config.registry.path = 'reg-path'
		config.registry.username = 'reg-usr'
		config.registry.password = 'reg-pw'

		config.registry.proxyUrl = 'reg-proxy-url'

		config.registry.proxyPath = 'reg-proxy-path'

		config.registry.proxyUsername = 'reg-proxy-usr'

		config.registry.proxyPassword = 'reg-proxy-pw'

		config.jenkins.internal = false
		config.jenkins.helm.version = '4.8.1'
		config.jenkins.username = 'jenusr'
		config.jenkins.password = 'jenpw'
		config.jenkins.url = 'http://jenkins'

		config.jenkins.metricsUsername = 'metrics-usr'

		config.jenkins.metricsPassword = 'metrics-pw'

		config.jenkins.skipPlugins = true
		config.jenkins.skipRestart = true

		install(createJenkins())

		Map<String, String> environment =
			getEnvAsMap()

		assertThat(commandExecutor.actualCommands[0])
			.isEqualTo("${System.getProperty('user.dir')}/" + 'scripts/jenkins/init-jenkins.sh')

		assertThat(environment['TRACE'])
			.isEqualTo('true')

		assertThat(environment['INTERNAL_JENKINS'])
			.isEqualTo('false')

		assertThat(environment['JENKINS_HELM_CHART_VERSION']).isEqualTo('4.8.1')

		assertThat(environment['JENKINS_URL'])
			.isEqualTo('http://jenkins')

		assertThat(environment['JENKINS_USERNAME'])
			.isEqualTo('jenusr')

		assertThat(environment['JENKINS_PASSWORD'])
			.isEqualTo('jenpw')

		assertThat(environment['NAME_PREFIX'])
			.isEqualTo('my-prefix-')

		assertThat(environment['INSECURE'])
			.isEqualTo('false')

		assertThat(environment['SCM_URL'])
			.isEqualTo('http://scmm.scm-manager.svc.cluster.local/scm')

		assertThat(environment['SCM_PASSWORD'])
			.isEqualTo(scmManagerMock.credentials.password)

		assertThat(environment['INSTALL_ARGOCD'])
			.isEqualTo('true')

		assertThat(environment['SKIP_PLUGINS'])
			.isEqualTo('true')

		assertThat(environment['SKIP_RESTART'])
			.isEqualTo('true')

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_SCM_URL',
				'http://scmm.scm-manager.svc.cluster.local/scm')

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_K8S_VERSION',
				Config.K8S_VERSION)

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_REGISTRY_URL',
				'reg-url')

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_REGISTRY_PATH',
				'reg-path')

		verify(globalPropertyManager, never())
			.setGlobalProperty(eq('MY_PREFIX_REGISTRY_PROXY_URL'),
				anyString())

		verify(globalPropertyManager, never())
			.setGlobalProperty(eq('MY_PREFIX_REGISTRY_PROXY_PATH'),
				anyString())

		verify(userManager)
			.createUser('metrics-usr',
				'metrics-pw')

		verify(userManager)
			.grantPermission('metrics-usr',
				UserManager.Permissions.METRICS_VIEW)
	}

	@Test
	void 'does not configure Prometheus for external Jenkins'() {
		config.features.monitoring.active = true
		config.jenkins.internal = false

		install(createJenkins())

		verify(prometheusConfigurator, never())
			.enableAuthentication()
	}

	@Test
	void 'does not configure Prometheus when monitoring is disabled'() {
		config.features.monitoring.active = false
		config.jenkins.internal = true

		install(createJenkins())

		verify(prometheusConfigurator, never())
			.enableAuthentication()
	}

	@Test
	void 'configures Prometheus for internal Jenkins when monitoring is enabled'() {
		config.features.monitoring.active = true
		config.jenkins.internal = true

		install(createJenkins())

		verify(prometheusConfigurator)
			.enableAuthentication()
	}

	@Test
	void 'uses Kubernetes service URL when running inside Kubernetes'() {
		config.jenkins.internal = true
		config.application.runningInsideK8s = true

		install(createJenkins())

		assertThat(config.jenkins.url)
			.isEqualTo('http://jenkins.jenkins.svc.cluster.local:80')
	}

	@Test
	void 'uses local cluster address and NodePort outside Kubernetes'() {
		config.jenkins.internal = true
		config.application.runningInsideK8s = false

		when(networkingUtils
			.findClusterBindAddress()).thenReturn('192.168.16.2')

		when(k8sClient.waitForNodePort(anyString(),
			anyString())).thenReturn('42')

		install(createJenkins())

		assertThat(config.jenkins.url)
			.endsWith('192.168.16.2:42')
	}

	@Test
	void 'sets proxy registry global properties for two registries'() {
		config.registry.twoRegistries = true
		config.application.namePrefix = 'my-prefix-'

		config.application.namePrefixForEnvVars = 'MY_PREFIX_'

		config.registry.url = 'reg-url'
		config.registry.path = 'reg-path'
		config.registry.proxyUrl = 'reg-proxy-url'

		config.registry.proxyPath = 'reg-proxy-path'

		install(createJenkins())

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_REGISTRY_PROXY_URL',
				'reg-proxy-url')

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_REGISTRY_PROXY_PATH',
				'reg-proxy-path')
	}

	@Test
	void 'does not create metrics user for security realm without local users'() {
		when(userManager
			.isUsingSecurityRealmWithoutLocalUserCreation()).thenReturn(true)

		install(createJenkins())

		verify(userManager, never())
			.createUser(anyString(),
				anyString())

		verify(userManager)
			.grantPermission(config.jenkins.metricsUsername,
				UserManager.Permissions.METRICS_VIEW)
	}

	@Test
	void 'sets global properties for additional environments'() {
		config.jenkins.additionalEnvs = [ADDITIONAL_DOCKER_RUN_ARGS: '-u0:0']

		install(createJenkins())

		verify(globalPropertyManager)
			.setGlobalProperty('ADDITIONAL_DOCKER_RUN_ARGS',
				'-u0:0')
	}

	@Test
	void 'does not create jobs during regular Jenkins setup'() {
		config.features.argocd.active = false

		install(createJenkins())

		verify(jobManager, never())
			.createCredential(anyString(),
				anyString(),
				anyString(),
				anyString(),
				anyString())

		verify(jobManager, never())
			.startJob(anyString())
	}

	@Test
	void 'sets Maven Central mirror global property'() {
		config.jenkins.mavenCentralMirror = 'http://test'

		config.application.namePrefixForEnvVars = 'MY_PREFIX_'

		install(createJenkins())

		verify(globalPropertyManager)
			.setGlobalProperty('MY_PREFIX_MAVEN_CENTRAL_MIRROR',
				'http://test')
	}

	@Test
	void 'creates Jenkins job with SCM Manager credentials'() {
		Jenkins jenkins = createJenkins()

		install(jenkins)

		jenkins.createJenkinsjob('example-apps',
			'my-app')

		verify(jobManager)
			.createJob('my-app',
				scmManagerMock.url,
				'example-apps',
				'scm-user')

		verify(jobManager)
			.createCredential('my-app',
				'scm-user',
				'gitops',
				config.scm.scmManager.password,
				'credentials for accessing scm-manager')

		verify(jobManager)
			.startJob('my-app')
	}

	protected Map<String, String> getEnvAsMap() {
		return commandExecutor.environment
			.collectEntries {
				it.split('=')
			} as Map<String, String>
	}

	private HelmToolDeploymentRequest executeAndCaptureRequest() {
		install(createJenkins())

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(HelmToolDeploymentRequest)

		verify(helmToolDeployer)
			.deploy(captor.capture(),
				eq(deploymentContext),
				eq(repositoryWorkspace))

		return captor.value
	}

	private Jenkins createJenkins() {
		TestGitRepoFactory repoFactory =
			new TestGitRepoFactory(config,
				fileSystemUtils) {
				@Override
				GitRepo create(String repoTarget,
					GitProvider scm) {
					GitRepo repo =
						super.create(repoTarget,
							scm)

					localTempDir = new File(repo.absoluteLocalRepoTmpDir)

					return repo
				}
			}

		GitRepo clusterResourcesRepo =
			repoFactory.create('argocd/cluster-resources',
				scmManagerMock)

		repositoryWorkspace = spy(new RepositoryWorkspace(clusterResourcesRepo))

		doNothing()
			.when(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges(anyString())

		return new Jenkins(commandExecutor,
			fileSystemUtils,
			globalPropertyManager,
			jobManager,
			userManager,
			prometheusConfigurator,
			helmToolDeployer,
			k8sClient,
			networkingUtils,
			gitHandler,
			imagePullSecretCreator)
	}

	private boolean install(Jenkins jenkins) {
		deploymentContext = new ContextBuilder(config).build()

		return jenkins.execute(deploymentContext,
			repositoryWorkspace)
	}
}