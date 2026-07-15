package com.cloudogu.gitops.infrastructure.deployment.helm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper

@CompileStatic
@Slf4j
@Singleton
class HelmValuesRenderer {

	private final FileSystemUtils fileSystemUtils

	HelmValuesRenderer(FileSystemUtils fileSystemUtils) {
		this.fileSystemUtils = fileSystemUtils
	}

	/**
	 * Creates the final Helm values by reading or rendering the configured
	 * values file and merging it with explicitly configured Helm values.	*/
	Map render(Config.HelmConfigWithValues helmConfig,
		String helmValuesPath,
		Map<String, Object> templateData) {
		Map renderedValues = templateData ?: [:]

		if (helmValuesPath) {
			renderedValues = readValues(helmValuesPath,
				templateData ?: [:])
		}

		return MapUtils.deepMerge(helmConfig.values ?: [:],
			renderedValues)
	}

	private Map readValues(String helmValuesPath,
		Map<String, Object> templateData) {
		if (helmValuesPath.endsWith('.ftl') || helmValuesPath.contains('.ftl.')) {
			log.debug("Rendering Helm values template from ${helmValuesPath}")

			return renderTemplate(helmValuesPath,
				templateData)
		}

		log.debug("Reading plain Helm values YAML from ${helmValuesPath}")

		return fileSystemUtils.readYaml(Path.of(helmValuesPath)) as Map
	}

	private static Map renderTemplate(String filePath,
		Map<String, Object> templateData) {
		String renderedContent = new TemplatingEngine()
			.template(new File(filePath),
				templateData)

		if (renderedContent.trim().isEmpty()) {
			/*
			 * YamlSlurper returns an empty list for an empty document,
			 * but Helm values are expected to be represented as a map.
			 */
			return [:]
		}

		return new YamlSlurper()
			.parseText(renderedContent) as Map
	}
}