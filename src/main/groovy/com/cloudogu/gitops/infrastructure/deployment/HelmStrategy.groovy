package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.infrastructure.helm.HelmClient

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
class HelmStrategy implements DeploymentStrategy {

	private final HelmClient helmClient

	HelmStrategy(HelmClient helmClient) {
		this.helmClient = helmClient
	}

	@Override
	void deployFeature(String repoURL,
		String repoName,
		String chartOrPath,
		String version,
		String namespace,
		String releaseName,
		Path helmValuesPath,
		RepoType repoType,
		DeploymentContext context,
		RepositoryWorkspace repositoryWorkspace) {
		deployFeature(repoURL,
			repoName,
			chartOrPath,
			version,
			namespace,
			releaseName,
			helmValuesPath,
			repoType)
	}

	void deployFeature(String repoURL,
		String repoName,
		String chartOrPath,
		String version,
		String namespace,
		String releaseName,
		Path helmValuesPath,
		RepoType repoType) {
		if (repoType == RepoType.GIT) {
			throw new RuntimeException('Unable to deploy Helm chart via Helm CLI from Git URL, ' + 'because Helm does not support this out of the box.\n' + "Repo URL: ${repoURL}")
		}

		log.debug("Imperatively deploying Helm release ${releaseName} " + "based on chart ${chartOrPath} from ${repoURL}, " +
			"version ${version}, into namespace ${namespace}. " +
			"Using values:\n${helmValuesPath.toFile().text}")

		helmClient.addRepo(repoName, repoURL)

		helmClient.upgrade(releaseName,
			"$repoName/$chartOrPath",
			[namespace: namespace,
			 version  : version,
			 values   : helmValuesPath.toString()])
	}
}