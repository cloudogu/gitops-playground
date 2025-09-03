package com.cloudogu.gitops.git.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.scm.ScmHandler
import com.cloudogu.gitops.git.ISCM
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class ScmRepoProvider {
    protected final Config config
    protected final FileSystemUtils fileSystemUtils
    protected ScmHandler scmProvider

    ScmRepoProvider(Config config, FileSystemUtils fileSystemUtils, ScmHandler scmProvider) {
        this.fileSystemUtils = fileSystemUtils
        this.config = config
        this.scmProvider = scmProvider
    }

    GitRepo getRepo(ISCM scm, String repoTarget) {
        return new GitRepo(config, scm, repoTarget, fileSystemUtils)
    }
}