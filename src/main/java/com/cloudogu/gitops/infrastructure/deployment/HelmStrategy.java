package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class HelmStrategy implements DeploymentStrategy {

	private final Config config;
	private final HelmClient helmClient;

	@Override
	public void deployFeature(String repoURL,
	                          String repoName,
	                          String chartOrPath,
	                          String version,
	                          String namespace,
	                          String releaseName,
	                          Path helmValuesPath,
	                          RepoType repoType,
	                          DeploymentContext context,
	                          RepositoryWorkspace repositoryWorkspace) {
		deployFeature(repoURL, repoName, chartOrPath, version, namespace, releaseName, helmValuesPath, repoType);
	}

	public void deployFeature(String repoURL,
	                          String repoName,
	                          String chartOrPath,
	                          String version,
	                          String namespace,
	                          String releaseName,
	                          Path helmValuesPath,
	                          RepoType repoType) {

		if (repoType == RepoType.GIT) {
			throw new IllegalArgumentException("Unable to deploy helm chart via Helm CLI from Git URL, because helm does not support this out of the box.\n" + "Repo URL: " + repoURL);
		}

		try {
			String valuesText = Files.readString(helmValuesPath);
			log.debug("Imperatively deploying helm release {} basing on chart {} from {}, version {}, into namespace {}. Using values:\n{}", releaseName, chartOrPath, repoURL, version, namespace, valuesText);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		helmClient.addRepo(repoName, repoURL);
		helmClient.upgrade(releaseName, repoName + "/" + chartOrPath, Map.of("namespace", namespace, "version", version, "values", helmValuesPath.toString()));
	}
}
