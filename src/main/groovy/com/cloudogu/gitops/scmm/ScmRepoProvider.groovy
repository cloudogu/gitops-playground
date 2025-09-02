package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scm.ISCM
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

    com.cloudogu.gitops.scm.ScmRepo getRepo(ISCM scm, String repoTarget) {
        return new com.cloudogu.gitops.scm.ScmRepo(config,scm,repoTarget, fileSystemUtils)
    }
}