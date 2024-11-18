package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class ScmmRepoProvider {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils

    ScmmRepoProvider(Config config, FileSystemUtils fileSystemUtils) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
    }

    ScmmRepo getRepo(String repoTarget) {
        return new ScmmRepo(config ,repoTarget, fileSystemUtils)
    }
}
