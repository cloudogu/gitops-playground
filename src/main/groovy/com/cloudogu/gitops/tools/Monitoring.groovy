package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.common.ToolWithImage
import com.cloudogu.gitops.utils.AirGappedUtils
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
class Monitoring extends Tool implements ToolWithImage {

	static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml'
	static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = 'argocd/cluster-resources/apps/monitoring/templates/rbac/namespace-isolation-rbac.ftl.yaml'
	static final String NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE = 'argocd/cluster-resources/apps/monitoring/templates/netpols/prometheus-allow-scraping.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = 'argocd/cluster-resources'
	private static final String TOOL_NAME = 'monitoring'
	private static final String MONITORING_APP_PATH = 'apps/monitoring'
	private static final String MONITORING_RBAC_PATH = "${MONITORING_APP_PATH}/misc/rbac"
	private static final String MONITORING_NETPOLS_PATH = "${MONITORING_APP_PATH}/misc/netpols"
	private static final String MONITORING_DASHBOARD_PATH = "${MONITORING_APP_PATH}/misc/dashboard"

	String namespace
	final K8sClient k8sClient

	Monitoring(FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler) {
		this.fileSystemUtils = fileSystemUtils
		this.deployer = deployer
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.monitoring.active
	}

	@Override
	protected void prepare() {
		this.namespace = activeNamespace(context)
	}

	@Override
	protected String activeNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}${context.config.features.monitoring.namespace}"
	}

	@Override
	void enable() {
		String uid = ''
		if (context.isOpenshift()) {
			uid = findValidOpenShiftUid()
		}

		addHelmValuesData('monitoring',
			[grafana: [host: config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl).host : '']])
		addHelmValuesData('namespaces', (config.application.namespaces.activeNamespaces ?: []) as LinkedHashSet<String>)
		addHelmValuesData('scm', scmConfigurationMetrics())
		addHelmValuesData('jenkins', jenkinsConfigurationMetrics())
		addHelmValuesData('uid', uid)

		// Create secrets imperatively here instead of values.yaml, because we don't want credentials to be visible in the Git repo
		setupMonitoringSecrets()
		createMonitoringCrd()

		prepareMonitoringApp(repositoryWorkspace.clusterResourcesRepository)
		replaceMonitoringTemplates(repositoryWorkspace.clusterResourcesRepository)
		writeMonitoringGitOpsArtifacts(repositoryWorkspace.clusterResourcesRepository)

		deployHelmChart(TOOL_NAME,
			'kube-prometheus-stack',
			namespace,
			config.features.monitoring.helm,
			HELM_VALUES_PATH,
			context)

		repositoryWorkspace.commitAndPushClusterResourcesChanges("Update ${TOOL_NAME} GitOps resources")
	}

	private void prepareMonitoringApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing Monitoring repository content in ${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, MONITORING_APP_PATH))
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

		// Remove dashboards for features that are not enabled
		cleanupUnusedDashboards(clusterResourcesRepo)
	}

	private void setupMonitoringSecrets() {
		k8sClient.createSecret('generic',
			'prometheus-metrics-creds-scmm',
			namespace,
			new Tuple2('password', config.application.password))

		k8sClient.createSecret('generic',
			'prometheus-metrics-creds-jenkins',
			namespace,
			new Tuple2('password', config.jenkins.metricsPassword),)

		if (config.features.mail.smtpUser || config.features.mail.smtpPassword) {
			k8sClient.createSecret('generic',
				'grafana-email-secret',
				namespace,
				new Tuple2('user', config.features.mail.smtpUser),
				new Tuple2('password', config.features.mail.smtpPassword))
		}
	}

	private void generateNamespaceIsolationRBAC(GitRepo clusterResourcesRepo) {
		for (String currentNamespace : config.application.namespaces.activeNamespaces) {
			String rbacYaml = new TemplatingEngine().template(new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
				[namespace : currentNamespace,
				 namePrefix: config.application.namePrefix,
				 config    : config,])

			clusterResourcesRepo.writeFile("${MONITORING_RBAC_PATH}/${currentNamespace}.yaml",
				rbacYaml)
		}
	}

	private void generateNetpols(GitRepo clusterResourcesRepo) {
		for (String currentNamespace : config.application.namespaces.activeNamespaces) {
			String netpolsYaml = new TemplatingEngine().template(new File(NETWORK_POLICIES_PROMETHEUS_ALLOW_TEMPLATE),
				[namespace : currentNamespace,
				 namePrefix: config.application.namePrefix,])

			clusterResourcesRepo.writeFile("${MONITORING_NETPOLS_PATH}/${currentNamespace}.yaml",
				netpolsYaml)
		}
	}

	private Map scmConfigurationMetrics() {
		URI uri = this.gitHandler.resourcesScm.prometheusMetricsEndpoint()
		return [protocol: uri?.scheme ?: '',
		        host    : uri?.authority ?: '',
		        path    : uri?.path ?: '',]
	}

	protected void createMonitoringCrd() {
		if (!config.application.skipCrds) {
			def serviceMonitorCrdYaml
			if (context.isAirgapped()) {
				serviceMonitorCrdYaml = Path.of("${config.application.localHelmChartFolder}/${config.features.monitoring.helm.chart}/charts/crds/crds/crd-servicemonitors.yaml").toString()
			} else {
				serviceMonitorCrdYaml = 'https://raw.githubusercontent.com/prometheus-community/helm-charts/' + "kube-prometheus-stack-${config.features.monitoring.helm.version}/" +
					"charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml"
			}

			log.debug('Applying ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.\n' + "Applying from path ${serviceMonitorCrdYaml}")
			k8sClient.applyYaml(serviceMonitorCrdYaml)
		}
	}

	private Map jenkinsConfigurationMetrics() {
		URI uri = baseUriJenkins(config).resolve('prometheus')
		return [metricsUsername: config.jenkins.metricsUsername ?: '',
		        protocol       : uri.scheme ?: '',
		        host           : uri.authority ?: '',
		        path           : uri.path ?: '',]
	}

	private static URI baseUriJenkins(Config config) {
		if (config.jenkins.internal) {
			return new URI("http://jenkins.${config.application.namePrefix}${config.jenkins.namespace}.svc.cluster.local/")
		}
		def urlString = config.jenkins?.url?.strip() ?: ''
		if (!urlString) {
			throw new IllegalArgumentException('config.jenkins.url must be set when config.jenkins.internal = false')
		}
		def url = URI.create(urlString)
		return url.toString().endsWith('/') ? url : URI.create(url.toString() + '/')
	}

	private String findValidOpenShiftUid() {
		String uidRange = k8sClient.getAnnotation('namespace', namespace, 'openshift.io/sa.scc.uid-range')

		if (uidRange) {
			log.debug("found UID=${uidRange}")
			String uid = uidRange.split('/')[0]
			return uid
		} else {
			throw new RuntimeException('Could not find a valid UID! Really running on OpenShift?')
		}
	}

	protected void cleanupUnusedDashboards(GitRepo clusterResourcesRepo) {
		String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
		String dashboardRoot = "${repoRoot}/${MONITORING_DASHBOARD_PATH}"

		if (!config.features.ingress.active) {
			fileSystemUtils.deleteFile("${dashboardRoot}/traefik-dashboard.yaml")
			fileSystemUtils.deleteFile("${dashboardRoot}/traefik-dashboard-requests-handling.yaml")
		}

		if (!config.jenkins.active) {
			fileSystemUtils.deleteFile("${dashboardRoot}/jenkins-dashboard.yaml")
		}

		if (!hasScmManagerMetricsEndpoint()) {
			fileSystemUtils.deleteFile("${dashboardRoot}/scmm-dashboard.yaml")
		}
	}

	@Override
	String getNamespace() {
		return namespace
	}

	@Override
	K8sClient getK8sClient() {
		return k8sClient
	}

	private boolean hasScmManagerMetricsEndpoint() {
		URI uri = this.gitHandler.resourcesScm.prometheusMetricsEndpoint()

		if (uri == null) {
			return false
		}

		return hasText(uri.scheme) || hasText(uri.authority) || hasText(uri.path)
	}

	private static boolean hasText(String value) {
		return value != null && value.trim()
	}
}