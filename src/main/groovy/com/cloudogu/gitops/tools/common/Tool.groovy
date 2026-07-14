package com.cloudogu.gitops.tools.common

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import java.nio.file.Path
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper

import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder

/**
 * A single tool to be deployed by GOP.
 *
 * The DeploymentOrchestrator controls the order of tools.
 * Each tool implements its own lifecycle phases.*/
@Slf4j
abstract class Tool {

	protected FileSystemUtils fileSystemUtils
	protected Deployer deployer
	protected AirGappedUtils airGappedUtils
	protected GitHandler gitHandler
	protected DeploymentContext context
	protected RepositoryWorkspace repositoryWorkspace
	protected Map<String, Object> helmValuesTemplateData = [:]

	/**
	 * Activation check for the current deployment run.
	 *
	 * This method must be side-effect free.
	 * Do not add deployment preparation, config mutation or workspace access here.	*/
	abstract boolean isEnabled(DeploymentContext context)

	/**
	 * Executes this tool along its internal lifecycle.	*/
	boolean execute(DeploymentContext context, RepositoryWorkspace workspace) {
		prepareExecution(context, workspace)

		log.info("Installing Tool ${getClass().getSimpleName()}")

		validate()
		preDeploy()
		deploy()
		postDeploy()
		publishChanges()

		log.info("Tool installed: ${getClass().getSimpleName()}")
		return true
	}

	/**
	 * Technical initialization of runtime state.
	 *
	 * This is not a lifecycle phase.
	 * Tool-specific preparation belongs into preDeploy().	*/
	protected void prepareExecution(DeploymentContext context,
		RepositoryWorkspace workspace) {
		this.context = context
		this.repositoryWorkspace = workspace
		this.helmValuesTemplateData = [:]
	}

	/**
	 * Lifecycle phase: validate tool-specific configuration and prerequisites.
	 *
	 * Throw a RuntimeException to stop the deployment immediately.	*/
	void validate() {}

	/**
	 * Lifecycle phase: prepare deployment inputs and prerequisites.	*/
	protected void preDeploy() {}

	/**
	 * Lifecycle phase: deploy the tool.	*/
	protected void deploy() {}

	/**
	 * Lifecycle phase: run follow-up steps after deployment.	*/
	protected void postDeploy() {}

	/**
	 * Lifecycle phase: publish GitOps repository changes.	*/
	protected void publishChanges() {}

	protected void publishClusterResourcesChanges(String toolName) {
		repositoryWorkspace.commitAndPushClusterResourcesChanges("Update ${toolName} GitOps resources")
	}

	protected void addHelmValuesData(String key, Object value) {
		this.helmValuesTemplateData[key] = value
	}

	/**
	 * Returns the namespace used by an enabled tool.
	 *
	 * Tools without a dedicated namespace return null.	*/
	String getActiveNamespace(DeploymentContext context) {
		if (!isEnabled(context)) {
			return null
		}

		return resolveNamespace(context)
	}

	/**
	 * Resolves the namespace used by this tool.
	 *
	 * Tools with a dedicated namespace override this method.
	 * The method must be side-effect free.	*/
	protected String resolveNamespace(DeploymentContext context) {
		return null
	}

	static Map templateToMap(String filePath, Map parameters) {
		def hydratedString = new TemplatingEngine()
			.template(new File(filePath), parameters)

		if (hydratedString.trim().isEmpty()) {
			// Otherwise YamlSlurper returns an empty array,
			// whereas we expect a Map.
			return [:]
		}

		return new YamlSlurper().parseText(hydratedString) as Map
	}

	protected void deployHelmChart(String featureName,
		String releaseName,
		String namespace,
		Config.HelmConfigWithValues helmConfig,
		String helmValuesTemplatePath,
		DeploymentContext context,
		boolean initByHelm = false) {
		Config config = context.config
		String repoURL = helmConfig.repoURL
		String chartOrPath = helmConfig.chart
		String version = helmConfig.version
		RepoType repoType = RepoType.HELM

		addHelmValuesData('config', config)
		addHelmValuesData('statics',
			new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32)
				.build()
				.getStaticModels())

		Map helmValuesData = helmValuesTemplateData

		if (helmValuesTemplatePath) {
			String helmValuesPath = helmValuesTemplatePath.toString()

			if (helmValuesPath.contains('.ftl')) {
				log.debug("Rendering helm values template from ${helmValuesTemplatePath}")
				helmValuesData = templateToMap(helmValuesTemplatePath,
					helmValuesTemplateData)
			} else {
				log.debug("Reading plain helm values YAML from ${helmValuesTemplatePath}")
				helmValuesData = fileSystemUtils.readYaml(Path.of(helmValuesTemplatePath)) as Map
			}
		}

		helmValuesData = MapUtils.deepMerge(helmConfig.values,
			helmValuesData)

		Path tempValuesPath = fileSystemUtils.writeTempFile(helmValuesData)

		if (context.isAirgapped()) {
			log.debug('Using a local, mirrored git repo as deployment source ' + "for feature ${featureName}")

			String repoNamespaceAndName =
				airGappedUtils.mirrorHelmRepoToGit(helmConfig)

			repoURL = gitHandler.resourcesScm.repoUrl(repoNamespaceAndName)
			chartOrPath = '.'
			repoType = RepoType.GIT
			version = new YamlSlurper()
				.parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
					'Chart.yaml'))['version']
		}

		log.debug("Starting deployment of feature ${featureName} from ${repoURL}.")
		log.debug("helm values used: ${helmValuesData}")

		deployer.deployFeature(repoURL,
			featureName,
			chartOrPath,
			version,
			namespace,
			releaseName,
			tempValuesPath,
			repoType,
			initByHelm,
			context,
			repositoryWorkspace)
	}

	Config getConfig() {
		return context.config
	}

	DeploymentContext getContext() {
		return context
	}

	/**
	 * Hook for preConfigInit. Optional.
	 * Feature should throw RuntimeException to stop immediately.	*/
	void preConfigInit(Config configToSet) {}

	/**
	 * Hook for postConfigInit. Optional.
	 * Feature should throw RuntimeException to stop immediately.	*/
	void postConfigInit(Config configToSet) {}
}