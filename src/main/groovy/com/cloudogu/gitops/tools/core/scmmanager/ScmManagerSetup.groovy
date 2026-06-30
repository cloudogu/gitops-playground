package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerUser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import java.nio.file.Path
import groovy.util.logging.Slf4j

import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder

@Slf4j
class ScmManagerSetup {

	private static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/scm-manager/templates/values.ftl.yaml'

	private final ScmManagerProvider scmManager
	private final Deployer deployer
	private final DeploymentContext context

	private Path tempValuesPath

	ScmManagerSetup(ScmManagerProvider scmManager,
		Deployer deployer,
		DeploymentContext context) {
		this.scmManager = scmManager
		this.deployer = deployer
		this.context = context
	}

	private Config getConfig() {
		return context.config
	}

	void setupHelm() {
		Path valuesPath = prepareHelmValues()
		def helmConfig = this.scmManager.scmmConfig.helm
		String releaseName = scmmReleaseName()

		log.info("Deploying SCM-Manager via Helm with releaseName='{}', namespace='{}', namePrefix='{}', dedicatedInstance={}",
			releaseName,
			this.scmManager.scmmConfig.namespace,
			config.application.namePrefix,
			context.isMultiTenant())

		deployer.helmStrategy.deployFeature(helmConfig.repoURL as String,
			'scm-manager',
			helmConfig.chart as String,
			helmConfig.version as String,
			this.scmManager.scmmConfig.namespace,
			releaseName,
			valuesPath,
			DeploymentStrategy.RepoType.HELM)
	}

	void createArgocdApplication() {
		Path valuesPath = tempValuesPath ?: prepareHelmValues()
		def helmConfig = this.scmManager.scmmConfig.helm
		String releaseName = scmmReleaseName()

		log.info("Creating SCM-Manager ArgoCD application with releaseName='{}', namespace='{}', namePrefix='{}', dedicatedInstance={}",
			releaseName,
			this.scmManager.scmmConfig.namespace,
			config.application.namePrefix,
			context.isMultiTenant())

		deployer.argoCdStrategyProvider.get().deployFeature(helmConfig.repoURL as String,
			'scm-manager',
			helmConfig.chart as String,
			helmConfig.version as String,
			this.scmManager.scmmConfig.namespace,
			releaseName,
			valuesPath,
			DeploymentStrategy.RepoType.HELM)
	}

	private Path prepareHelmValues() {
		String releaseName = scmmReleaseName()

		log.info("Preparing SCM-Manager Helm values with releaseName='{}', namespace='{}'",
			releaseName,
			this.scmManager.scmmConfig.namespace)

		Map<String, Object> templateVars = [config     : this.scmManager.config,
		                                    host       : this.scmManager.scmmConfig.ingress,
		                                    username   : this.scmManager.scmmConfig.credentials.username,
		                                    password   : this.scmManager.scmmConfig.credentials.password,
		                                    helm       : this.scmManager.scmmConfig.helm,
		                                    releaseName: releaseName,
		                                    statics    : new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels()]

		Map templatedMap = TemplatingEngine.templateToMap(HELM_VALUES_PATH, templateVars)
		Map values = this.scmManager.scmmConfig.helm.values as Map ?: [:]

		Map mergedMap = MapUtils.deepMerge(values, templatedMap)
		tempValuesPath = new FileSystemUtils().writeTempFile(mergedMap)

		return tempValuesPath
	}

	private String scmmReleaseName() {
		def prefix = (config.application.namePrefix ?: '').strip()

		if (prefix) {
			return "${prefix}scmm"
		}

		return 'scmm'
	}

	void waitForScmmAvailable(int timeoutSeconds = 180, int intervalMillis = 5000, int startDelay = 0) {
		long startTime = System.currentTimeMillis()
		long timeoutMillis = timeoutSeconds * 1000L

		if (startDelay > 0) {
			sleep(startDelay)
		}

		while (System.currentTimeMillis() - startTime < timeoutMillis) {
			try {
				def call = scmManager.getApiClient().generalApi().checkScmmAvailable()
				def response = call.execute()

				if (response.successful) {
					log.info('SCM-Manager is available.')
					return
				}
			} catch (Exception e) {
				log.debug("Waiting for SCM-Manager... Error: ${e.message}")
			}

			sleep(intervalMillis)
		}

		throw new RuntimeException("Timeout: SCM-Manager did not respond with 200 OK within ${timeoutSeconds} seconds")
	}

	void configure() {
		installScmmPlugins()
		setSetupConfigs()

		if (this.scmManager.config.jenkins.active) {
			configureJenkinsPlugin()
		}

		addDefaultUsers()

		log.info('ScmManager Setup finished!')
	}

	private void installScmmPlugins() {
		if (this.scmManager.config.scm.scmManager.skipPlugins) {
			log.debug('Skipping SCM plugin installation')
			return
		}

		List<String> pluginNames = ['scm-mail-plugin',
		                            'scm-review-plugin',
		                            'scm-code-editor-plugin',
		                            'scm-editor-plugin',
		                            'scm-landingpage-plugin',
		                            'scm-el-plugin',
		                            'scm-readme-plugin',
		                            'scm-webhook-plugin',
		                            'scm-ci-plugin',
		                            'scm-metrics-prometheus-plugin']

		if (this.scmManager.config.jenkins.active) {
			pluginNames.add('scm-jenkins-plugin')
		}

		boolean restartForThisPlugin = false

		pluginNames.each { String pluginName ->
			log.debug("Installing Plugin ${pluginName} ...")

			restartForThisPlugin = !this.scmManager.config.scm.scmManager.skipRestart && pluginName == pluginNames.last()

			ScmManagerApiClient.handleApiResponse(scmManager.getApiClient().pluginApi().install(pluginName, restartForThisPlugin))
		}

		log.debug('SCM-Manager plugin installation finished successfully!')

		if (restartForThisPlugin) {
			waitForScmmAvailable(180, 2000, 100)
		}
	}

	private void setSetupConfigs() {
		def setupConfigs = [enableProxy             : false,
		                    proxyPort               : 8080,
		                    proxyServer             : 'proxy.mydomain.com',
		                    proxyUser               : null,
		                    proxyPassword           : null,
		                    realmDescription        : 'SONIA :: SCM Manager',
		                    disableGroupingGrid     : false,
		                    dateFormat              : 'YYYY-MM-DD HH:mm:ss',
		                    anonymousAccessEnabled  : false,
		                    anonymousMode           : 'OFF',
		                    baseUrl                 : this.scmManager.url,
		                    forceBaseUrl            : false,
		                    loginAttemptLimit       : -1,
		                    proxyExcludes           : [],
		                    skipFailedAuthenticators: false,
		                    pluginUrl               : 'https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}',
		                    loginAttemptLimitTimeout: 300,
		                    enabledXsrfProtection   : true,
		                    namespaceStrategy       : 'CustomNamespaceStrategy',
		                    loginInfoUrl            : 'https://login-info.scm-manager.org/api/v1/login-info',
		                    releaseFeedUrl          : 'https://scm-manager.org/download/rss.xml',
		                    mailDomainName          : 'scm-manager.local',
		                    adminGroups             : [],
		                    adminUsers              : []]

		ScmManagerApiClient.handleApiResponse(scmManager.getApiClient().generalApi().setConfig(setupConfigs))

		log.debug('Successfully added SCMM Setup Configs')
	}

	private void configureJenkinsPlugin() {
		def jenkinsPluginConfig = [disableRepositoryConfiguration: false,
		                           disableMercurialTrigger       : false,
		                           disableGitTrigger             : false,
		                           disableEventTrigger           : false,
		                           url                           : this.scmManager.config.jenkins.urlForScm] as Map<String, Object>

		ScmManagerApiClient.handleApiResponse(this.scmManager.getApiClient().pluginApi().configureJenkinsPlugin(jenkinsPluginConfig))

		log.debug('Successfully configured JenkinsPlugin in SCM-Manager.')
	}

	private void addDefaultUsers() {
		String metricsUsername = "${this.scmManager.config.application.namePrefix}metrics"

		addUser(this.scmManager.scmmConfig.gitOpsUsername, this.scmManager.scmmConfig.password)
		addUser(metricsUsername, this.scmManager.scmmConfig.password)
		grantUserPermissions(metricsUsername, ['metrics:read'])
	}

	private void addUser(String username, String password, String email = 'changeme@test.local') {
		ScmManagerUser userRequest = [name       : username,
		                              displayName: username,
		                              mail       : email,
		                              external   : false,
		                              password   : password,
		                              active     : true,
		                              _links     : [:]]

		ScmManagerApiClient.handleApiResponse(scmManager.getApiClient().usersApi().addUser(userRequest))

		log.debug("Successfully created SCM-Manager User ${username}.")
	}

	private void grantUserPermissions(String username, List<String> permissions) {
		def permissionBody = [permissions: permissions]

		ScmManagerApiClient.handleApiResponse(scmManager.getApiClient().usersApi().setPermissionForUser(username, permissionBody))

		log.debug("Granted permissions ${permissions} to user ${username}.")
	}
}