package com.cloudogu.gitops.tools.core

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.infrastructure.jenkins.JobManager
import com.cloudogu.gitops.infrastructure.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.infrastructure.jenkins.UserManager
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils

import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
class Jenkins extends Tool {

	static final String HELM_VALUES_PATH =
		'argocd/cluster-resources/apps/jenkins/templates/values.ftl.yaml'

	private static final List<String> OIDC_BOOT_PLUGIN_NAMES =
		['oic-auth', 'json-path-api']

	private static final String CLUSTER_RESOURCES_SOURCE_DIR =
		'argocd/cluster-resources'
	private static final String TOOL_NAME = 'jenkins'
	private static final String JENKINS_APP_PATH = 'apps/jenkins'
	private static final String RELEASE_NAME = 'jenkins'

	private final CommandExecutor commandExecutor
	private final FileSystemUtils fileSystemUtils
	private final GlobalPropertyManager globalPropertyManager
	private final JobManager jobManager
	private final UserManager userManager
	private final PrometheusConfigurator prometheusConfigurator
	private final HelmToolDeployer helmToolDeployer
	private final K8sClient k8sClient
	private final NetworkingUtils networkingUtils
	private final GitHandler gitHandler
	private final ImagePullSecretCreator imagePullSecretCreator

	private Map<String, Object> helmTemplateData = [:]

	String namespace

	Jenkins(CommandExecutor commandExecutor,
		FileSystemUtils fileSystemUtils,
		GlobalPropertyManager globalPropertyManager,
		JobManager jobManager,
		UserManager userManager,
		PrometheusConfigurator prometheusConfigurator,
		HelmToolDeployer helmToolDeployer,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator) {
		this.commandExecutor = commandExecutor
		this.fileSystemUtils = fileSystemUtils
		this.globalPropertyManager = globalPropertyManager
		this.jobManager = jobManager
		this.userManager = userManager
		this.prometheusConfigurator = prometheusConfigurator
		this.helmToolDeployer = helmToolDeployer
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils
		this.gitHandler = gitHandler
		this.imagePullSecretCreator = imagePullSecretCreator
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.jenkins.active
	}

	@Override
	protected void preDeploy() {
		if (!isInternalJenkins()) {
			return
		}

		this.namespace = resolveNamespace(context)
		this.helmTemplateData = [:]

		createImagePullSecret()
		createJenkinsNamespace()
		labelJenkinsNode()
		createJenkinsCredentialsSecret()
		prepareJenkinsHelmValues()
		prepareJenkinsApp(repositoryWorkspace.clusterResourcesRepository)
	}

	@Override
	protected void deploy() {
		if (!isInternalJenkins()) {
			return
		}

		deployInternalJenkins()
	}

	@Override
	protected void postDeploy() {
		if (isInternalJenkins()) {
			updateJenkinsUrl()
		}

		runSetupScript()
	}

	@Override
	protected void publishChanges() {
		if (!isInternalJenkins()) {
			return
		}

		publishClusterResourcesChanges(TOOL_NAME)
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		if (!context.config.jenkins.internal) {
			return null
		}

		return "${context.config.application.namePrefix}" + "${context.config.jenkins.namespace}"
	}

	private boolean isInternalJenkins() {
		return config.jenkins.internal
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(config,
			namespace)
	}

	private void createJenkinsNamespace() {
		k8sClient.createNamespace(namespace)
	}

	private void labelJenkinsNode() {
		/*
		 * Mark the first node for Jenkins and agents.
		 * See jenkins/values.ftl.yaml "agent.workingDir".
		 */
		k8sClient.labelRemove('node',
			'--all',
			'',
			'node')

		String nodeName =
			k8sClient.waitForNode()
				.replace('node/', '')

		k8sClient.label('node',
			nodeName,
			new Tuple2('node', 'jenkins'))
	}

	private void createJenkinsCredentialsSecret() {
		k8sClient.createSecret('generic',
			'jenkins-credentials',
			namespace,
			new Tuple2('jenkins-admin-user',
				config.jenkins.username),
			new Tuple2('jenkins-admin-password',
				config.jenkins.password))
	}

	private void prepareJenkinsHelmValues() {
		helmTemplateData['dockerGid'] = findDockerGid()

		helmTemplateData['jenkinsBootPlugins'] = jenkinsOidcConfigured() ? getJenkinsOidcBootPlugins() : []
	}

	private void deployInternalJenkins() {
		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				RELEASE_NAME,
				namespace,
				config.jenkins.helm,
				HELM_VALUES_PATH,
				helmTemplateData,
				true)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)
	}

	private void updateJenkinsUrl() {
		String serviceName = RELEASE_NAME

		if (config.application.runningInsideK8s) {
			log.debug('Setting Jenkins URL to Kubernetes service, ' + 'since the installation is running inside Kubernetes')

			config.jenkins.url = networkingUtils.createUrl(serviceName + '.' + namespace + '.svc.cluster.local',
				'80')
		} else {
			log.debug('Setting Jenkins configuration for a local single-node ' + 'cluster with internal Jenkins. Waiting for NodePort...')

			String port =
				k8sClient.waitForNodePort(serviceName,
					namespace)

			String clusterBindAddress =
				networkingUtils.findClusterBindAddress()

			config.jenkins.url = networkingUtils.createUrl(clusterBindAddress,
				port)
		}
	}

	private void prepareJenkinsApp(GitRepo clusterResourcesRepo) {
		log.debug('Preparing Jenkins repository content in ' + "${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR,
				JENKINS_APP_PATH))
	}

	private void runSetupScript() {
		commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/jenkins/init-jenkins.sh",
			[TRACE                     : config.application.trace,
			 INTERNAL_JENKINS          : config.jenkins.internal,
			 JENKINS_HELM_CHART_VERSION: config.jenkins.helm.version,
			 JENKINS_URL               : config.jenkins.url,
			 JENKINS_USERNAME          : config.jenkins.username,
			 JENKINS_PASSWORD          : config.jenkins.password,
			 SCM_URL                   : gitHandler.tenant.url,
			 PREFIXED_SCM_URL          : gitHandler.tenant.repoPrefix(),
			 SCM_PASSWORD              : gitHandler.tenant.credentials.password,
			 SCM_PROVIDER              : config.scm.scmProviderType,
			 INSTALL_ARGOCD            : config.features.argocd.active,
			 NAME_PREFIX               : config.application.namePrefix,
			 INSECURE                  : config.application.insecure,
			 SKIP_RESTART              : config.jenkins.skipRestart,
			 SKIP_PLUGINS              : config.jenkins.skipPlugins])

		globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}SCM_URL",
			gitHandler.tenant.url)

		globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}PREFIXED_SCM_URL",
			gitHandler.tenant.repoPrefix())

		if (config.jenkins.additionalEnvs) {
			for (entry in
				(config.jenkins.additionalEnvs as Map).entrySet()) {
				globalPropertyManager.setGlobalProperty(entry.key.toString(),
					entry.value.toString())
			}
		}

		if (config.registry.url) {
			globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_URL",
				config.registry.url)
		}

		if (config.registry.path) {
			globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PATH",
				config.registry.path)
		}

		if (config.registry.twoRegistries) {
			globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PROXY_URL",
				config.registry.proxyUrl)

			globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PROXY_PATH",
				config.registry.proxyPath)
		}

		if (config.jenkins.mavenCentralMirror) {
			globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}MAVEN_CENTRAL_MIRROR",
				config.jenkins.mavenCentralMirror)
		}

		globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}K8S_VERSION",
			Config.K8S_VERSION)

		if (userManager
			.isUsingSecurityRealmWithoutLocalUserCreation()) {
			log.trace('Using a security realm without local user creation. ' + 'Must not create user.')
		} else {
			userManager.createUser(config.jenkins.metricsUsername,
				config.jenkins.metricsPassword)
		}

		userManager.grantPermission(config.jenkins.metricsUsername,
			UserManager.Permissions.METRICS_VIEW)

		if (config.features.monitoring.active && config.jenkins.internal) {
			prometheusConfigurator.enableAuthentication()
		}
	}

	void createJenkinsjob(String namespace,
		String repoName) {
		String credentialId = 'scm-user'
		String prefixedNamespace =
			"${config.application.namePrefix}${namespace}"
		String jobName =
			"${config.application.namePrefix}${repoName}"

		jobManager.createJob(jobName,
			gitHandler.tenant.url,
			prefixedNamespace,
			credentialId)

		if (config.scm.scmProviderType == ScmProviderType.SCM_MANAGER) {
			jobManager.createCredential(jobName,
				credentialId,
				"${config.application.namePrefix}gitops",
				"${config.scm.getScmManager().password}",
				'credentials for accessing scm-manager')
		}

		if (config.scm.scmProviderType == ScmProviderType.GITLAB) {
			jobManager.createCredential(jobName,
				credentialId,
				"${config.scm.getGitlab().username}",
				"${config.scm.getGitlab().password}",
				'credentials for accessing gitlab')
		}

		jobManager.createCredential(jobName,
			'registry-user',
			"${config.registry.username}",
			"${config.registry.password}",
			'credentials for accessing the docker-registry ' + 'for writing images built on Jenkins')

		if (config.registry.twoRegistries) {
			jobManager.createCredential(jobName,
				'registry-proxy-user',
				"${config.registry.proxyUsername}",
				"${config.registry.proxyPassword}",
				'credentials for accessing the docker-registry ' + 'that contains third-party or base images')
		}

		jobManager.startJob(jobName)
	}

	private boolean jenkinsOidcConfigured() {
		return config.jenkins.oidc?.trim()
	}

	private List<String> getJenkinsOidcBootPlugins() {
		File pluginsFile = new File("${fileSystemUtils.rootDir}/" + 'scripts/jenkins/plugins/plugins.txt')

		Map<String, String> pinnedPlugins = [:]

		pluginsFile.eachLine { String line ->
			String pluginDefinition = line.trim()

			if (pluginDefinition && !pluginDefinition.startsWith('#')) {
				String pluginName =
					pluginDefinition.split(':', 2)[0]

				if (OIDC_BOOT_PLUGIN_NAMES.contains(pluginName)) {
					pinnedPlugins[pluginName] = pluginDefinition
				}
			}
		}

		List<String> missingPlugins =
			OIDC_BOOT_PLUGIN_NAMES.findAll {
				!pinnedPlugins.containsKey(it)
			}

		if (missingPlugins) {
			throw new IllegalStateException('Required Jenkins OIDC boot plugins missing from ' + "${pluginsFile}: ${missingPlugins.join(', ')}")
		}

		return OIDC_BOOT_PLUGIN_NAMES.collect {
			pinnedPlugins[it]
		}
	}

	protected String findDockerGid() {
		String gid = ''

		def etcGroup = k8sClient.run('tmp-docker-gid-grepper-' + "${new Random().nextInt(10000)}",
			'irrelevant',
			namespace,
			createGidGrepperOverrides(),
			'--restart=Never',
			'-ti',
			'--rm',
			'--quiet')

		def lines = etcGroup?.split('\n')

		for (String line : lines) {
			def parts = line.split(':')

			if (parts[0] == 'docker') {
				gid = parts[2]
				break
			}
		}

		if (!gid) {
			log.warn('Unable to determine Docker Group ID (GID). ' + 'Jenkins Agent pods will run as root user (UID 0)!\n' + "Group docker not found in /etc/group:\n${etcGroup}")

			return ''
		}

		log.debug("Using Docker Group ID (GID) ${gid} " + 'for Jenkins Agent pods')

		return gid
	}

	Map createGidGrepperOverrides() {
		return [spec: [containers  : [[name        : 'tmp-docker-gid-grepper',
		                               image       : config.jenkins.internalBashImage,
		                               args        : ['cat', '/etc/group'],
		                               volumeMounts: [[name     : 'group',
		                                               mountPath: '/etc/group',
		                                               readOnly : true]]]],
		               nodeSelector: [node: 'jenkins'],
		               volumes     : [[name    : 'group',
		                               hostPath: [path: '/etc/group']]]]] as Map
	}
}