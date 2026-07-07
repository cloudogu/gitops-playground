package com.cloudogu.gitops.infrastructure.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils

import jakarta.inject.Singleton

@Singleton
class GitRepoFactory {
	protected final Config config
	protected final FileSystemUtils fileSystemUtils

	GitRepoFactory(Config config, FileSystemUtils fileSystemUtils) {
		this.config = config
		this.fileSystemUtils = fileSystemUtils
	}

	GitRepo create(String repoTarget, GitProvider gitProvider) {
		return new GitRepo(config, gitProvider, repoTarget, fileSystemUtils)
	}

}