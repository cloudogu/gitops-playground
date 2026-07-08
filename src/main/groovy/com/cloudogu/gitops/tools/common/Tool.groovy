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
 * A single tool to be deployed by GOP.*/
@Slf4j
abstract class Tool {

	protected FileSystemUtils fileSystemUtils
	protected Deployer deployer
	protected AirGappedUtils airGappedUtils
	protected GitHandler gitHandler
	protected DeploymentContext context
	protected RepositoryWorkspace repositoryWorkspace
	protected Map<String, Object> helmValuesTemplateData = [:]

	protected void addHelmValuesData(String key, Object value) {
		this.helmValuesTemplateData[key] = value
	}

	/**
	 * Activation check for the current deployment run.
	 * Do not add deployment preparation, config mutation or workspace access here.	*/
	abstract boolean isEnabled(DeploymentContext context)

	boolean execute(DeploymentContext context, RepositoryWorkspace workspace) {
		this.context = context
		this.repositoryWorkspace = workspace
		prepare()

		log.info("Installing Tool ${getClass().getSimpleName()}")

		createImagePullSecretIfRequired()

		enable()

		log.info("Tool installed: ${getClass().getSimpleName()}")
		return true
	}

	protected void createImagePullSecretIfRequired() {
		if (this instanceof ToolWithImage) {
			(this as ToolWithImage).createImagePullSecret()
		}
	}

	protected void prepare() {}

	String getActiveNamespaceFromFeature(DeploymentContext context) {
		// using reflection to get all subclasses implementing an own namespace
		if (this.metaClass.hasProperty(this, 'namespace')) {
			return isEnabled(context) ? activeNamespace(context) : null
		}
		return null
	}

	protected String activeNamespace(DeploymentContext context) {
		return this.getProperty('namespace')
	}

	static Map templateToMap(String filePath, Map parameters) {
		def hydratedString = new TemplatingEngine().template(new File(filePath), parameters)

		if (hydratedString.trim().isEmpty()) {
			// Otherwise YamlSlurper returns an empty array, whereas we expect a Map
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

		this.addHelmValuesData("config", config)
		this.addHelmValuesData("statics", new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels())

		/*
		 * If we get a helmValuesTemplatePath we render the Template with the given Data.
		 * Some Features might not use a values template and thus passing no helmValuesTemplatePath,
		 * in that case we simply treat helmValuesTemplateData directly as helmValuesData.
		 */
		Map helmValuesData = this.helmValuesTemplateData
		if (helmValuesTemplatePath) {
			def helmValuesPath = helmValuesTemplatePath.toString()
			if (helmValuesPath.contains(".ftl")) {
				log.debug("Rendering helm values template from ${helmValuesTemplatePath}")
				helmValuesData = templateToMap(helmValuesTemplatePath, this.helmValuesTemplateData)
			} else {
				log.debug("Reading plain helm values YAML from ${helmValuesTemplatePath}")
				helmValuesData = fileSystemUtils.readYaml(Path.of(helmValuesTemplatePath)) as Map
			}
		}

		helmValuesData = MapUtils.deepMerge(helmConfig.values, helmValuesData)
		Path tempValuesPath = this.fileSystemUtils.writeTempFile(helmValuesData)

		if (context.isAirgapped()) {
			log.debug("Using a local, mirrored git repo as deployment source for feature ${featureName}")

			String repoNamespaceAndName = this.airGappedUtils.mirrorHelmRepoToGit(helmConfig)
			repoURL = this.gitHandler.resourcesScm.repoUrl(repoNamespaceAndName)
			chartOrPath = '.'
			repoType = RepoType.GIT
			version = new YamlSlurper()
				.parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}", 'Chart.yaml'))['version']
		}

		log.debug("Starting deployment of feature ${featureName} from ${repoURL}.")
		log.debug("helm values used: ${helmValuesData}")

		this.deployer.deployFeature(repoURL,
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

	/*
	 * Hooks for enabling or disabling a feature. Both optional, because not always needed.
	 */

	protected void enable() {}

	protected void disable() {}

	/*
	 * Hook for special feature validation. Optional.
	 * Feature should throw RuntimeException to stop immediately.
	 */

	void validate() {}

	/**
	 * Hook for preConfigInit. Optional.
	 * Feature should throw RuntimeException to stop immediately.	*/
	void preConfigInit(Config configToSet) {}

	/**
	 * Hook for postConfigInit. Optional.
	 * Feature should throw RuntimeException to stop immediately.	*/
	void postConfigInit(Config configToSet) {}
}