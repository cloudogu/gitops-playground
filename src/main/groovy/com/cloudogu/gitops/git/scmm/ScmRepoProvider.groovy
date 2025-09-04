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
    protected GitHandler scmProvider

    ScmRepoProvider(Config config, FileSystemUtils fileSystemUtils, GitHandler scmProvider) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
        this.scmProvider = scmProvider
    }

    GitRepo getRepo(GitProvider scm, String repoTarget) {
        return new GitRepo(config, scm, repoTarget, fileSystemUtils)
    }
}