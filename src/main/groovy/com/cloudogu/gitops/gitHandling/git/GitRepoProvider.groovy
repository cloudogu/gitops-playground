package com.cloudogu.gitops.gitHandling.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitHandling.gitServerClients.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class GitRepoProvider {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils
    private final GitProvider gitProvider

    GitRepoProvider(Config config, FileSystemUtils fileSystemUtils, GitProvider gitProvider) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
        this.gitProvider = gitProvider
    }

    GitRepo getRepo(String repoTarget) {
        return new GitRepo(config, gitProvider, repoTarget, fileSystemUtils)
    }

    GitRepo getRepo(String repoTarget, Boolean isCentralRepo) {
        return new GitRepo(config, gitProvider, repoTarget, fileSystemUtils, isCentralRepo)
    }
}