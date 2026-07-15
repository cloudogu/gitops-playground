package com.cloudogu.gitops.infrastructure.deployment.helm

import static com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper

import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder

@CompileStatic
@Slf4j
@Singleton
class HelmToolDeployer {

	private final Deployer deployer
	private final FileSystemUtils fileSystemUtils
	private final AirGappedUtils airGappedUtils
	private final GitHandler gitHandler
	private final HelmValuesRenderer helmValuesRenderer

	HelmToolDeployer(Deployer deployer,
		FileSystemUtils fileSystemUtils,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		HelmValuesRenderer helmValuesRenderer) {
		this.deployer = deployer
		this.fileSystemUtils = fileSystemUtils
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
		this.helmValuesRenderer = helmValuesRenderer
	}

	void deploy(HelmToolDeploymentRequest request,
		DeploymentContext context,
		RepositoryWorkspace repositoryWorkspace) {
		Config config = context.config

		Map<String, Object> templateData =
			new LinkedHashMap<>(request.templateData)

		/*
		 * Preserve the template variables that were previously added
		 * by Tool.deployHelmChart().
		 */
		templateData['config'] = config
		templateData['statics'] = new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32)
			.build()
			.getStaticModels()

		Map helmValues = helmValuesRenderer.render(request.helmConfig,
			request.helmValuesPath,
			templateData)

		Path temporaryValuesPath =
			fileSystemUtils.writeTempFile(helmValues)

		ResolvedHelmSource source = resolveSource(request,
			context)

		log.debug("Starting deployment of tool ${request.toolName} " + "from ${source.repoUrl}.")
		log.debug("Helm values used: ${helmValues}")

		deployer.deployFeature(source.repoUrl,
			request.toolName,
			source.chartOrPath,
			source.version,
			request.namespace,
			request.releaseName,
			temporaryValuesPath,
			source.repoType,
			request.bootstrapWithHelm,
			context,
			repositoryWorkspace)
	}

	private ResolvedHelmSource resolveSource(HelmToolDeploymentRequest request,
		DeploymentContext context) {
		Config.HelmConfigWithValues helmConfig = request.helmConfig

		if (!context.isAirgapped()) {
			return new ResolvedHelmSource(helmConfig.repoURL,
				helmConfig.chart,
				helmConfig.version,
				RepoType.HELM)
		}

		log.debug('Using a local mirrored Git repository as deployment source ' + "for tool ${request.toolName}")

		String repositoryNamespaceAndName =
			airGappedUtils.mirrorHelmRepoToGit(helmConfig)

		String repositoryUrl =
			gitHandler.resourcesScm.repoUrl(repositoryNamespaceAndName)

		String chartVersion = readMirroredChartVersion(context.config,
			helmConfig)

		return new ResolvedHelmSource(repositoryUrl,
			'.',
			chartVersion,
			RepoType.GIT)
	}

	private static String readMirroredChartVersion(Config config,
		Config.HelmConfigWithValues helmConfig) {
		Path chartFile = Path.of("${config.application.localHelmChartFolder}/${helmConfig.chart}",
			'Chart.yaml')

		return new YamlSlurper()
			.parse(chartFile)['version'] as String
	}

	@CompileStatic
	private static class ResolvedHelmSource {

		final String repoUrl
		final String chartOrPath
		final String version
		final RepoType repoType

		ResolvedHelmSource(String repoUrl,
			String chartOrPath,
			String version,
			RepoType repoType) {
			this.repoUrl = repoUrl
			this.chartOrPath = chartOrPath
			this.version = version
			this.repoType = repoType
		}
	}
}