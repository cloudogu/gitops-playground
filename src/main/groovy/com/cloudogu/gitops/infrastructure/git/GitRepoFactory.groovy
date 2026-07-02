package com.cloudogu.gitops.infrastructure.git

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils

import jakarta.inject.Singleton

@Singleton
class GitRepoFactory {
	protected final DeploymentContext context
	protected final FileSystemUtils fileSystemUtils

	GitRepoFactory(DeploymentContext context, FileSystemUtils fileSystemUtils) {
		this.fileSystemUtils = fileSystemUtils
		this.context = context
	}

	GitRepo create(String repoTarget, GitProvider gitProvider) {
		return new GitRepo(context, gitProvider, repoTarget, fileSystemUtils)
	}

}