package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class GitRepoFactory {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils

    GitRepoFactory(Config config, FileSystemUtils fileSystemUtils) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
    }

    GitRepo getRepo(String repoTarget, GitProvider gitProvider) {
        return new GitRepo(config, gitProvider, repoTarget, fileSystemUtils)
    }

}