package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import java.nio.file.Path;

public interface DeploymentStrategy {

void deployFeature(
	String repoURL,
	String repoName,
	String chartOrPath,
	String version,
	String namespace,
	String releaseName,
	Path helmValuesPath,
	RepoType repoType,
	DeploymentContext context,
	RepositoryWorkspace repositoryWorkspace);

default void deployFeature(
	String repoURL,
	String repoName,
	String chart,
	String version,
	String namespace,
	String releaseName,
	Path helmValuesPath,
	DeploymentContext context,
	RepositoryWorkspace repositoryWorkspace) {
	deployFeature(
		repoURL,
		repoName,
		chart,
		version,
		namespace,
		releaseName,
		helmValuesPath,
		RepoType.HELM,
		context,
		repositoryWorkspace);
}

enum RepoType {
	HELM,
	GIT
}
}
