package com.cloudogu.gitops.infrastructure.git

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils

import jakarta.inject.Singleton

@Singleton
class GitRepoFactory {
	protected final FileSystemUtils fileSystemUtils

	GitRepoFactory(FileSystemUtils fileSystemUtils) {
		this.fileSystemUtils = fileSystemUtils
	}

	GitRepo create(DeploymentContext context, String repoTarget, GitProvider gitProvider) {
		return new GitRepo(context, gitProvider, repoTarget, fileSystemUtils)
	}

}