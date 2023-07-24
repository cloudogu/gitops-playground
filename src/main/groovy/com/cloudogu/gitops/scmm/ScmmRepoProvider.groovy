package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import jakarta.inject.Singleton

@Singleton
class ScmmRepoProvider {
    protected final Configuration configuration
    private final CommandExecutor commandExecutor
    private final FileSystemUtils fileSystemUtils

    ScmmRepoProvider(Configuration configuration, CommandExecutor commandExecutor, FileSystemUtils fileSystemUtils) {
        this.fileSystemUtils = fileSystemUtils
        this.commandExecutor = commandExecutor
        this.configuration = configuration
    }

    ScmmRepo getRepo(String repoTarget) {
        return new ScmmRepo(configuration.getConfig(), repoTarget, commandExecutor, fileSystemUtils)
    }
}
