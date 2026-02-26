package com.cloudogu.gitops

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
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
 * Typically, this is a helm chart (see {@link com.cloudogu.gitops.features.deployment.DeploymentStrategy} and 
 * {@code downloadHelmCharts.sh}) with its own section in the config
 * (see {@link com.cloudogu.gitops.config.schema.Schema#features}).<br/><br/>
 *
 * In the config, features typically set their default helm chart coordinates and provide options to
 * <ul>
 *   <li>configure images</li>
 *   <li>overwrite default helm values</li>
 * </ul><br/>
 *
 * In addition to their own config, features react to several generic GOP config options.<br/>
 * Here are some typical examples:
 * <ul>
 *   <li>Mirror the Helm Chart: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#mirrorRepos} see {@link com.cloudogu.gitops.utils.AirGappedUtils#mirrorHelmRepoToGit(java.util.Map)} </li>
 *   <li>Create Image Pull Secrets: {@link com.cloudogu.gitops.config.schema.Schema.RegistrySchema#createImagePullSecrets} see {@link FeatureWithImage}</li>
 *   <li>Install with Network Policies: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#netpols}</li>
 *   <li>Install with Resource requests + limits: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#podResources}</li>
 *   <li>Install without CRDs: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#skipCrds}</li>
 *   <li>For apps with UI: Setting {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#username} and {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#password}</li>
 * </ul>*/

@Slf4j
abstract class Feature {

	protected FileSystemUtils fileSystemUtils
	protected DeploymentStrategy deployer
	protected AirGappedUtils airGappedUtils
	protected GitHandler gitHandler
	protected Map<String, Object> helmValuesTemplateData = [:]

	protected void addHelmValuesData(String key, Object value) {
		this.helmValuesTemplateData[key] = value
	}

	boolean install() {
		if (isEnabled()) {
			log.info("Installing Feature ${getClass().getSimpleName()}")

			if (this instanceof FeatureWithImage) {
				(this as FeatureWithImage).createImagePullSecret()
			}

			enable()
			return true
		} else {
			log.debug("Feature ${getClass().getSimpleName()} is disabled")
			disable()
			return false
		}
	}

	String getActiveNamespaceFromFeature() {
		//using reflection to get all subclasses implementing a own namespace
		if (this.metaClass.hasProperty(this, 'namespace')) {
			return isEnabled() ? this.getProperty('namespace') : null
		}
		return null
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
		Config config) {
		String repoURL = helmConfig.repoURL
		String chartOrPath = helmConfig.chart
		String version = helmConfig.version
		RepoType repoType = RepoType.HELM

		this.addHelmValuesData("config", config)
		this.addHelmValuesData("statics", new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build().getStaticModels())

		/* If we get a helmValuesTemplatePath we render the Template with the given Data.
		 * Some Features might not use a values template and thus passing no helmValuesTemplatePath, in that
		 * case we simply treat helmValuesTemplateData directly as helmValuesData */
		Map helmValuesData = this.helmValuesTemplateData
		if (helmValuesTemplatePath) {
			log.debug("got helm_value_path, rendering values template")
			helmValuesData = templateToMap(helmValuesTemplatePath, this.helmValuesTemplateData)
		}

		helmValuesData = MapUtils.deepMerge(helmConfig.values, helmValuesData)
		Path tempValuesPath = this.fileSystemUtils.writeTempFile(helmValuesData)

		if (config.application.mirrorRepos) {
			log.debug("Using a local, mirrored git repo as deployment source for feature ${featureName}")

			String repoNamespaceAndName = this.airGappedUtils.mirrorHelmRepoToGit(helmConfig)
			repoURL = this.gitHandler.resourcesScm.repoUrl(repoNamespaceAndName)
			chartOrPath = '.'
			repoType = RepoType.GIT
			version = new YamlSlurper()
				.parse(Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
					'Chart.yaml'))['version']
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
			repoType)
	}

	abstract boolean isEnabled()

	/*
	 *  Hooks for enabling or disabling a feature. Both optional, because not always needed.
	 */

	protected void enable() {}

	protected void disable() {}

	/*
	 * Hook for special feature validation. Optional.
	 * Feature should throw RuntimeException to stop immediately.
	 */

	protected void validate() {}

	/**
	 * Hook for preConfigInit. Optional.
	 * Feature should throw RuntimeException to stop immediately.*/
	void preConfigInit(Config configToSet) {}

	/**
	 * Hook for postConfigInit. Optional.
	 * Feature should throw RuntimeException to stop immediately.*/
	void postConfigInit(Config configToSet) {}
}