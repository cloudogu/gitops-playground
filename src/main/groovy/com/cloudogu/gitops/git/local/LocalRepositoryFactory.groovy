package com.cloudogu.gitops.git.local

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class LocalRepositoryFactory {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils
    private final GitProvider gitProvider

    LocalRepositoryFactory(Config config, FileSystemUtils fileSystemUtils, GitProvider gitProvider) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
        this.gitProvider = gitProvider
    }

    LocalRepository getRepo(String repoTarget) {
        return new LocalRepository(config, gitProvider, repoTarget, fileSystemUtils)
    }

    LocalRepository getRepo(String repoTarget, Boolean isCentralRepo) {
        return new LocalRepository(config, gitProvider, repoTarget, fileSystemUtils, isCentralRepo)
    }
}