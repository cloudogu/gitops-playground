package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.utils.FileSystemUtils

class ScmRepo {

    private ISCM scm

    Config config

    ScmRepo(Config config, ISCM scm, String scmmRepoTarget, FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.config = config
        this.scm = scm
        this.scm.credentials
        this.insecure = config.application.insecure
        this.gitName = config.application.gitName
        this.gitEmail = config.application.gitEmail
    }
}