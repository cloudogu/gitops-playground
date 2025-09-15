package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitHandling.gitServerClients.GitProvider
import com.cloudogu.gitops.gitHandling.git.GitRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class ScmmRepoProvider {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils
    private final GitProvider gitProvider

    ScmmRepoProvider(Config config, FileSystemUtils fileSystemUtils, GitProvider gitProvider) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
        this.gitProvider = gitProvider
    }

    GitRepo getRepo(String repoTarget) {
        return new ScmmRepo(config, gitProvider, repoTarget, fileSystemUtils)
    }

    GitRepo getRepo(String repoTarget, Boolean isCentralRepo) {
        return new ScmmRepo(config, gitProvider, repoTarget, fileSystemUtils, isCentralRepo)
    }
}