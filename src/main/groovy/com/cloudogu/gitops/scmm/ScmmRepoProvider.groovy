package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class ScmmRepoProvider {
    protected final Configuration configuration
    protected final FileSystemUtils fileSystemUtils

    ScmmRepoProvider(Configuration configuration, FileSystemUtils fileSystemUtils) {
        this.fileSystemUtils = fileSystemUtils
        this.configuration = configuration
    }

    ScmmRepo getRepo(String repoTarget) {
        return new ScmmRepo(configuration.getConfig(), repoTarget, fileSystemUtils)
    }
}
