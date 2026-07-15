package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import io.micronaut.core.annotation.Order

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(300)
@CompileStatic
class Monitoring extends Tool {

	static final String HELM_VALUES_PATH =
		'argocd/cluster-resources/apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml'

	static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE =
		'argocd/cluster-resources/apps/monitoring/templates/rbac/namespace-isolation-rbac.ftl.yaml'

	static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE =
		'argocd/cluster-resources/apps/monitoring/templates/netpols/prometheus-allow-scraping.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR =
		'argocd/cluster-resources'

	private static final String TOOL_NAME = 'monitoring'
	private static final String RELEASE_NAME = 'kube-prometheus-stack'
	private static final String MONITORING_APP_PATH = 'apps/monitoring'
	private static final String MONITORING_RBAC_PATH =
		"${MONITORING_APP_PATH}/misc/rbac"
	private static final String MONITORING_NETPOLS_PATH =
		"${MONITORING_APP_PATH}/misc/netpols"
	private static final String MONITORING_DASHBOARD_PATH =
		"${MONITORING_APP_PATH}/misc/dashboard"

	private final HelmToolDeployer helmToolDeployer
	private final FileSystemUtils fileSystemUtils
	private final K8sClient k8sClient
	private final GitHandler gitHandler
	private final ImagePullSecretCreator imagePullSecretCreator

	private Map<String, Object> helmTemplateData = [:]

	String namespace

	Monitoring(HelmToolDeployer helmToolDeployer,
		FileSystemUtils fileSystemUtils,
		K8sClient k8sClient,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator) {
		this.helmToolDeployer = helmToolDeployer
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.gitHandler = gitHandler
		this.imagePullSecretCreator = imagePullSecretCreator
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.monitoring.active
	}

	@Override
	protected void preDeploy() {
		this.namespace = resolveNamespace(context)
		this.helmTemplateData = [:]

		createImagePullSecret()
		prepareMonitoringHelmValues()

		/*
		 * Create secrets imperatively instead of writing credentials
		 * into the GitOps repository.
		 */
		setupMonitoringSecrets()
		createMonitoringCrd()

		prepareMonitoringApp(repositoryWorkspace.clusterResourcesRepository)
		replaceMonitoringTemplates(repositoryWorkspace.clusterResourcesRepository)
		writeMonitoringGitOpsArtifacts(repositoryWorkspace.clusterResourcesRepository)
	}

	@Override
	protected void deploy() {
		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				RELEASE_NAME,
				namespace,
				config.features.monitoring.helm,
				HELM_VALUES_PATH,
				helmTemplateData)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)
	}

	@Override
	protected void publishChanges() {
		publishClusterResourcesChanges(TOOL_NAME)
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}" + "${context.config.features.monitoring.namespace}"
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(config,
			namespace)
	}

	private void prepareMonitoringHelmValues() {
		String uid = context.isOpenshift() ? findValidOpenShiftUid() : ''

		String grafanaHost =
			config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl).host : ''

		helmTemplateData['monitoring'] = [grafana: [host: grafanaHost]]

		helmTemplateData['namespaces'] = (config.application.namespaces.activeNamespaces ?: [])
			as LinkedHashSet<String>

		helmTemplateData['scm'] = scmConfigurationMetrics()

		helmTemplateData['jenkins'] = jenkinsConfigurationMetrics()

		helmTemplateData['uid'] = uid
	}

	private void prepareMonitoringApp(GitRepo clusterResourcesRepo) {
		log.debug('Preparing Monitoring repository content in ' + "${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR,
				MONITORING_APP_PATH))
	}

	private void replaceMonitoringTemplates(GitRepo clusterResourcesRepo) {
		clusterResourcesRepo.replaceTemplates([config: config])
	}

	private void writeMonitoringGitOpsArtifacts(GitRepo clusterResourcesRepo) {
		if (config.application.namespaceIsolation) {
			generateNamespaceIsolationRBAC(clusterResourcesRepo)
		}

		if (config.application.netpols) {
			generateNetpols(clusterResourcesRepo)
		}

		cleanupUnusedDashboards(clusterResourcesRepo)
	}

	private void setupMonitoringSecrets() {
		k8sClient.createSecret('generic',
			'prometheus-metrics-creds-scmm',
			namespace,
			new Tuple2('password',
				config.application.password))

		k8sClient.createSecret('generic',
			'prometheus-metrics-creds-jenkins',
			namespace,
			new Tuple2('password',
				config.jenkins.metricsPassword))

		if (config.features.mail.smtpUser || config.features.mail.smtpPassword) {
			k8sClient.createSecret('generic',
				'grafana-email-secret',
				namespace,
				new Tuple2('user',
					config.features.mail.smtpUser),
				new Tuple2('password',
					config.features.mail.smtpPassword))
		}
	}

	private void generateNamespaceIsolationRBAC(GitRepo clusterResourcesRepo) {
		for (String currentNamespace :
			config.application.namespaces.activeNamespaces) {
			String rbacYaml =
				new TemplatingEngine().template(new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
					[namespace : currentNamespace,
					 namePrefix: config.application.namePrefix,
					 config    : config])

			clusterResourcesRepo.writeFile("${MONITORING_RBAC_PATH}/" + "${currentNamespace}.yaml",
				rbacYaml)
		}
	}

	private void generateNetpols(GitRepo clusterResourcesRepo) {
		for (String currentNamespace :
			config.application.namespaces.activeNamespaces) {
			String netpolsYaml =
				new TemplatingEngine().template(new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE),
					[namespace : currentNamespace,
					 namePrefix: config.application.namePrefix])

			clusterResourcesRepo.writeFile("${MONITORING_NETPOLS_PATH}/" + "${currentNamespace}.yaml",
				netpolsYaml)
		}
	}

	private Map scmConfigurationMetrics() {
		URI uri =
			gitHandler.resourcesScm.prometheusMetricsEndpoint()

		return [protocol: uri?.scheme ?: '',
		        host    : uri?.authority ?: '',
		        path    : uri?.path ?: '']
	}

	protected void createMonitoringCrd() {
		if (config.application.skipCrds) {
			return
		}

		String serviceMonitorCrdYaml

		if (context.isAirgapped()) {
			serviceMonitorCrdYaml = Path.of("${config.application.localHelmChartFolder}/" + "${config.features.monitoring.helm.chart}/" +
				'charts/crds/crds/crd-servicemonitors.yaml').toString()
		} else {
			serviceMonitorCrdYaml = 'https://raw.githubusercontent.com/' + 'prometheus-community/helm-charts/' +
				'kube-prometheus-stack-' +
				"${config.features.monitoring.helm.version}/" +
				'charts/kube-prometheus-stack/charts/' +
				'crds/crds/crd-servicemonitors.yaml'
		}

		log.debug('Applying ServiceMonitor CRD; Argo CD fails if it ' + 'is not there. Chicken-egg problem.\n' + "Applying from path ${serviceMonitorCrdYaml}")

		k8sClient.applyYaml(serviceMonitorCrdYaml)
	}

	private Map jenkinsConfigurationMetrics() {
		URI uri = baseUriJenkins(config)
			.resolve('prometheus')

		return [metricsUsername: config.jenkins.metricsUsername ?: '',
		        protocol       : uri.scheme ?: '',
		        host           : uri.authority ?: '',
		        path           : uri.path ?: '']
	}

	private static URI baseUriJenkins(Config config) {
		if (config.jenkins.internal) {
			return new URI('http://jenkins.' + "${config.application.namePrefix}" + "${config.jenkins.namespace}" + '.svc.cluster.local/')
		}

		String urlString =
			config.jenkins?.url?.strip() ?: ''

		if (!urlString) {
			throw new IllegalArgumentException('config.jenkins.url must be set when ' + 'config.jenkins.internal = false')
		}

		URI url = URI.create(urlString)

		return url.toString().endsWith('/') ? url : URI.create(url.toString() + '/')
	}

	private String findValidOpenShiftUid() {
		String uidRange = k8sClient.getAnnotation('namespace',
			namespace,
			'openshift.io/sa.scc.uid-range')

		if (!uidRange) {
			throw new RuntimeException('Could not find a valid UID! ' + 'Really running on OpenShift?')
		}

		log.debug("Found UID range ${uidRange}")

		return uidRange.split('/')[0]
	}

	protected void cleanupUnusedDashboards(GitRepo clusterResourcesRepo) {
		String repoRoot =
			clusterResourcesRepo
				.getAbsoluteLocalRepoTmpDir()

		String dashboardRoot =
			"${repoRoot}/${MONITORING_DASHBOARD_PATH}"

		if (!config.features.ingress.active) {
			fileSystemUtils.deleteFile("${dashboardRoot}/traefik-dashboard.yaml")
			fileSystemUtils.deleteFile("${dashboardRoot}/" + 'traefik-dashboard-requests-handling.yaml')
		}

		if (!config.jenkins.active) {
			fileSystemUtils.deleteFile("${dashboardRoot}/jenkins-dashboard.yaml")
		}

		if (!hasScmManagerMetricsEndpoint()) {
			fileSystemUtils.deleteFile("${dashboardRoot}/scmm-dashboard.yaml")
		}
	}

	private boolean hasScmManagerMetricsEndpoint() {
		URI uri =
			gitHandler.resourcesScm.prometheusMetricsEndpoint()

		if (uri == null) {
			return false
		}

		return hasText(uri.scheme) || hasText(uri.authority) || hasText(uri.path)
	}

	private static boolean hasText(String value) {
		return value != null && value.trim()
	}
}