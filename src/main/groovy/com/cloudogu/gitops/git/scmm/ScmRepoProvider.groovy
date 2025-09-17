package com.cloudogu.gitops.git.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitProvider
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class ScmRepoProvider {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils

    ScmRepoProvider(Config config, FileSystemUtils fileSystemUtils) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
    }

    GitRepo getRepo(String repoTarget) {
        return new GitRepo(config, repoTarget, fileSystemUtils)
    }

}