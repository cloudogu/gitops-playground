package com.cloudogu.gitops.tools

import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

@CompileStatic
class MonitoringTest {

	private Config config

	private final HelmToolDeployer helmToolDeployer =
		mock(HelmToolDeployer)

	private final FileSystemUtils fileSystemUtils =
		new FileSystemUtils()

	private final K8sClient k8sClient =
		mock(K8sClient)

	private final GitHandler gitHandler =
		mock(GitHandler)

	private final ImagePullSecretCreator imagePullSecretCreator =
		mock(ImagePullSecretCreator)

	private DeploymentContext deploymentContext
	private RepositoryWorkspace repositoryWorkspace
	private ScmManagerProviderMock scmManagerMock
	private File clusterResourcesRepoDir

	@BeforeEach
	void setup() {
		reset(
			helmToolDeployer,
			k8sClient,
			gitHandler,
			imagePullSecretCreator
		)

		config = createConfig()

		scmManagerMock =
			new ScmManagerProviderMock()

		when(
			gitHandler.getResourcesScm()
		).thenReturn(
			scmManagerMock
		)
	}

	@Test
	void 'is disabled via active flag'() {
		config.features.monitoring.active = false

		DeploymentContext context =
			new ContextBuilder(config).build()

		assertFalse(
			createMonitoring().isEnabled(context)
		)
	}

	@Test
	void 'is enabled via active flag'() {
		DeploymentContext context =
			new ContextBuilder(config).build()

		assertThat(
			createMonitoring().isEnabled(context)
		).isTrue()
	}

	@Test
	void 'creates expected Helm deployment request'() {
		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(request.toolName)
			.isEqualTo('monitoring')

		assertThat(request.releaseName)
			.isEqualTo('kube-prometheus-stack')

		assertThat(request.namespace)
			.isEqualTo('foo-monitoring')

		assertThat(request.helmConfig)
			.isSameAs(
				config.features.monitoring.helm
			)

		assertThat(request.helmValuesPath)
			.isEqualTo(
				Monitoring.HELM_VALUES_PATH
			)

		assertThat(request.bootstrapWithHelm)
			.isFalse()
	}

	@Test
	void 'uses configured namespace prefix'() {
		config.application.namePrefix = 'tenant-'

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(request.namespace)
			.isEqualTo('tenant-monitoring')
	}

	@Test
	void 'passes additional Helm values through request'() {
		config.features.monitoring.helm.values = [
			key: [
				some: 'thing',
				one : 1
			],
			prometheus: [
				prometheusSpec: [
					scrapeConfigSelectorNilUsesHelmValues:
						null
				]
			]
		]

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(request.helmConfig.values)
			.isEqualTo(
				config.features.monitoring.helm.values
			)
	}

	@Test
	void 'adds monitoring namespace and Grafana host to template data'() {
		config.features.monitoring.grafanaUrl =
			'http://grafana.local'

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			request.templateData['monitoring']
		).isEqualTo([
			grafana: [
				host: 'grafana.local'
			]
		])

		assertThat(
			request.templateData['namespaces']
		).isEqualTo(
			config.application.namespaces.activeNamespaces
				as LinkedHashSet<String>
		)
	}

	@Test
	void 'adds internal SCM Manager metrics endpoint to template data'() {
		scmManagerMock.prometheus =
			new URI(
				'http://localhost:8080/' +
					'scm/api/v2/metrics/prometheus'
			)

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			request.templateData['scm']
		).isEqualTo([
			protocol: 'http',
			host    : 'localhost:8080',
			path    :
				'/scm/api/v2/metrics/prometheus'
		])
	}

	@Test
	void 'adds internal Jenkins metrics endpoint to template data'() {
		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			request.templateData['jenkins']
		).isEqualTo([
			metricsUsername: 'metrics',
			protocol       : 'http',
			host           :
				'jenkins.foo-jenkins.svc.cluster.local',
			path           : '/prometheus'
		])
	}

	@Test
	void 'adds external Jenkins metrics endpoint to template data'() {
		config.jenkins.internal = false
		config.jenkins.url =
			'https://localhost:9090/jenkins'

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			request.templateData['jenkins']
		).isEqualTo([
			metricsUsername: 'metrics',
			protocol       : 'https',
			host           : 'localhost:9090',
			path           : '/jenkins/prometheus'
		])
	}

	@Test
	void 'adds custom Jenkins metrics username to template data'() {
		config.jenkins.metricsUsername =
			'external-metrics-username'

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			(request.templateData['jenkins'] as Map)
			['metricsUsername']
		).isEqualTo(
			'external-metrics-username'
		)
	}

	@Test
	void 'adds empty UID for Kubernetes deployment'() {
		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			request.templateData['uid']
		).isEqualTo('')
	}

	@Test
	void 'adds OpenShift UID to template data'() {
		config.application.openshift = true

		when(
			k8sClient.getAnnotation(
				'namespace',
				'foo-monitoring',
				'openshift.io/sa.scc.uid-range'
			)
		).thenReturn(
			'1000920000/10000'
		)

		HelmToolDeploymentRequest request =
			installAndCaptureRequest()

		assertThat(
			request.templateData['uid']
		).isEqualTo('1000920000')
	}

	@Test
	void 'creates image pull secret in monitoring namespace'() {
		install()

		verify(imagePullSecretCreator)
			.createIfRequired(
				config,
				'foo-monitoring'
			)
	}

	@Test
	void 'creates SCM Manager and Jenkins metric secrets'() {
		install()

		verify(k8sClient).createSecret(
			'generic',
			'prometheus-metrics-creds-scmm',
			'foo-monitoring',
			new Tuple2(
				'password',
				config.application.password
			)
		)

		verify(k8sClient).createSecret(
			'generic',
			'prometheus-metrics-creds-jenkins',
			'foo-monitoring',
			new Tuple2(
				'password',
				config.jenkins.metricsPassword
			)
		)
	}

	@Test
	void 'creates Grafana mail secret when SMTP credentials are configured'() {
		config.features.mail.smtpUser =
			'grafana@example.com'

		config.features.mail.smtpPassword =
			'secret'

		install()

		verify(k8sClient).createSecret(
			'generic',
			'grafana-email-secret',
			'foo-monitoring',
			new Tuple2(
				'user',
				'grafana@example.com'
			),
			new Tuple2(
				'password',
				'secret'
			)
		)
	}

	@Test
	void 'does not create Grafana mail secret without SMTP credentials'() {
		config.features.mail.smtpUser = null
		config.features.mail.smtpPassword = null

		install()

		verify(k8sClient, never())
			.createSecret(
				eq('generic'),
				eq('grafana-email-secret'),
				anyString(),
				any(Tuple2),
				any(Tuple2)
			)
	}

	@Test
	void 'applies Prometheus ServiceMonitor CRD from GitHub'() {
		config.application.mirrorRepos = false
		config.application.skipCrds = false

		install()

		verify(k8sClient).applyYaml(
			'https://raw.githubusercontent.com/' +
				'prometheus-community/helm-charts/' +
				'kube-prometheus-stack-19.2.2/' +
				'charts/kube-prometheus-stack/charts/' +
				'crds/crds/crd-servicemonitors.yaml'
		)
	}

	@Test
	void 'applies Prometheus ServiceMonitor CRD from local chart in air-gapped mode'() {
		config.application.mirrorRepos = true
		config.application.skipCrds = false

		Path rootChartsFolder =
			Files.createTempDirectory(
				this.class.simpleName
			)

		config.application.localHelmChartFolder =
			rootChartsFolder.toString()

		String expectedPath =
			rootChartsFolder.resolve(
				'kube-prometheus-stack/' +
					'charts/crds/crds/' +
					'crd-servicemonitors.yaml'
			).toString()

		install()

		verify(k8sClient).applyYaml(
			expectedPath
		)
	}

	@Test
	void 'does not apply ServiceMonitor CRD when CRDs are skipped'() {
		config.application.skipCrds = true

		install()

		verify(k8sClient, never())
			.applyYaml(anyString())
	}

	@Test
	void 'prepares monitoring app content without copying templates'() {
		install()

		assertThat(
			new File(
				clusterResourcesRepoDir,
				'apps/monitoring'
			)
		).exists()

		assertThat(
			new File(
				clusterResourcesRepoDir,
				'apps/monitoring/templates'
			)
		).doesNotExist()

		assertThat(
			new File(
				clusterResourcesRepoDir,
				'apps/monitoring/misc/dashboard'
			)
		).exists()
	}

	@Test
	void 'removes dashboards for disabled features'() {
		config.features.ingress.active = false
		config.jenkins.active = false
		scmManagerMock.prometheus = null

		install()

		File dashboardDirectory =
			new File(
				clusterResourcesRepoDir,
				'apps/monitoring/misc/dashboard'
			)

		assertThat(
			new File(
				dashboardDirectory,
				'traefik-dashboard.yaml'
			)
		).doesNotExist()

		assertThat(
			new File(
				dashboardDirectory,
				'traefik-dashboard-requests-handling.yaml'
			)
		).doesNotExist()

		assertThat(
			new File(
				dashboardDirectory,
				'jenkins-dashboard.yaml'
			)
		).doesNotExist()

		assertThat(
			new File(
				dashboardDirectory,
				'scmm-dashboard.yaml'
			)
		).doesNotExist()
	}

	@Test
	void 'keeps SCM Manager dashboard when metrics endpoint exists'() {
		config.features.ingress.active = false
		config.jenkins.active = false
		config.scm.scmManager.url = null

		scmManagerMock.prometheus =
			new URI(
				'http://localhost:8080/' +
					'scm/api/v2/metrics/prometheus'
			)

		install()

		File dashboardDirectory =
			new File(
				clusterResourcesRepoDir,
				'apps/monitoring/misc/dashboard'
			)

		assertThat(
			new File(
				dashboardDirectory,
				'traefik-dashboard.yaml'
			)
		).doesNotExist()

		assertThat(
			new File(
				dashboardDirectory,
				'traefik-dashboard-requests-handling.yaml'
			)
		).doesNotExist()

		assertThat(
			new File(
				dashboardDirectory,
				'jenkins-dashboard.yaml'
			)
		).doesNotExist()

		assertThat(
			new File(
				dashboardDirectory,
				'scmm-dashboard.yaml'
			)
		).exists()
	}

	@Test
	void 'creates namespace isolation RBAC resources'() {
		config.application.namespaceIsolation = true

		install()

		for (
			String namespace :
				config.application.namespaces.activeNamespaces
		) {
			File rbacFile =
				new File(
					clusterResourcesRepoDir,
					'apps/monitoring/misc/rbac/' +
						"${namespace}.yaml"
				)

			assertThat(rbacFile)
				.exists()

			assertThat(rbacFile.text)
				.contains(
					"namespace: ${namespace}"
				)
				.contains(
					'namespace: foo-monitoring'
				)
		}
	}

	@Test
	void 'does not create namespace isolation RBAC resources by default'() {
		install()

		assertThat(
			new File(
				clusterResourcesRepoDir,
				'apps/monitoring/misc/rbac'
			)
		).doesNotExist()
	}

	@Test
	void 'creates network policies for Prometheus'() {
		config.application.netpols = true

		install()

		for (
			String namespace :
				config.application.namespaces.activeNamespaces
		) {
			File networkPolicyFile =
				new File(
					clusterResourcesRepoDir,
					'apps/monitoring/misc/netpols/' +
						"${namespace}.yaml"
				)

			assertThat(networkPolicyFile)
				.exists()

			assertThat(networkPolicyFile.text)
				.contains(
					"namespace: ${namespace}"
				)
		}
	}

	@Test
	void 'delegates deployment to HelmToolDeployer'() {
		install()

		verify(helmToolDeployer).deploy(
			any(HelmToolDeploymentRequest),
			eq(deploymentContext),
			eq(repositoryWorkspace)
		)
	}

	@Test
	void 'publishes monitoring resources through repository workspace'() {
		install()

		verify(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges(
				'Update monitoring GitOps resources'
			)
	}

	private HelmToolDeploymentRequest installAndCaptureRequest() {
		install()

		ArgumentCaptor<HelmToolDeploymentRequest> captor =
			ArgumentCaptor.forClass(
				HelmToolDeploymentRequest
			)

		verify(helmToolDeployer).deploy(
			captor.capture(),
			eq(deploymentContext),
			eq(repositoryWorkspace)
		)

		return captor.value
	}

	private boolean install() {
		createWorkspace()

		deploymentContext =
			new ContextBuilder(config).build()

		return createMonitoring().execute(
			deploymentContext,
			repositoryWorkspace
		)
	}

	private Monitoring createMonitoring() {
		return new Monitoring(
			helmToolDeployer,
			fileSystemUtils,
			k8sClient,
			gitHandler,
			imagePullSecretCreator
		)
	}

	private void createWorkspace() {
		TestGitRepoFactory repositoryFactory =
			new TestGitRepoFactory(
				config,
				fileSystemUtils
			) {
				@Override
				GitRepo create(
					String repositoryTarget,
					GitProvider provider
				) {
					GitRepo repository =
						super.create(
							repositoryTarget,
							scmManagerMock
						)

					clusterResourcesRepoDir =
						new File(
							repository
								.absoluteLocalRepoTmpDir
						)

					createDashboardFiles(
						clusterResourcesRepoDir
					)

					return repository
				}
			}

		GitRepo clusterResourcesRepository =
			repositoryFactory.create(
				'argocd/cluster-resources',
				scmManagerMock
			)

		repositoryWorkspace =
			spy(
				new RepositoryWorkspace(
					clusterResourcesRepository
				)
			)

		doNothing()
			.when(repositoryWorkspace)
			.commitAndPushClusterResourcesChanges(
				anyString()
			)
	}

	private static void createDashboardFiles(
		File repositoryDirectory
	) {
		File dashboardDirectory =
			new File(
				repositoryDirectory,
				'apps/monitoring/misc/dashboard'
			)

		dashboardDirectory.mkdirs()

		new File(
			dashboardDirectory,
			'traefik-dashboard.yaml'
		).text = 'dummy'

		new File(
			dashboardDirectory,
			'traefik-dashboard-requests-handling.yaml'
		).text = 'dummy'

		new File(
			dashboardDirectory,
			'jenkins-dashboard.yaml'
		).text = 'dummy'

		new File(
			dashboardDirectory,
			'scmm-dashboard.yaml'
		).text = 'dummy'
	}

	private static Config createConfig() {
		return Config.fromMap(
			registry: [
				internal              : true,
				createImagePullSecrets: false
			],
			scm: [
				scmManager: [
					internal: true
				]
			],
			jenkins: [
				internal       : true,
				active         : true,
				metricsUsername: 'metrics',
				metricsPassword: 'metrics'
			],
			application: [
				username          : 'abc',
				password          : '123',
				openshift         : false,
				namePrefix        : 'foo-',
				mirrorRepos       : false,
				podResources      : false,
				skipCrds          : false,
				namespaceIsolation: false,
				gitName           : 'Cloudogu',
				gitEmail          :
					'hello@cloudogu.com',
				netpols           : false,
				namespaces        : [
					dedicatedNamespaces: [
						'test1-default',
						'test1-argocd',
						'test1-monitoring',
						'test1-secrets'
					] as LinkedHashSet,
					tenantNamespaces   : [
						'test1-example-apps-staging',
						'test1-example-apps-production'
					] as LinkedHashSet
				]
			],
			features: [
				argocd: [
					active: true
				],
				monitoring: [
					active          : true,
					grafanaUrl      : '',
					grafanaEmailFrom:
						'grafana@example.org',
					grafanaEmailTo  :
						'infra@example.org',
					helm            : [
						chart  :
							'kube-prometheus-stack',
						repoURL:
							'https://prom',
						version:
							'19.2.2'
					]
				],
				secrets: [
					active: true
				],
				ingress: [
					active: true
				]
			]
		)
	}
}