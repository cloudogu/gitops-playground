package com.cloudogu.gitops.infrastructure.git;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.utils.FileSystemUtils;
import jakarta.inject.Singleton;

@Singleton
public class GitRepoFactory {
    protected final Config config;
    protected final FileSystemUtils fileSystemUtils;

    public GitRepoFactory(Config config, FileSystemUtils fileSystemUtils) {
        this.config = config;
        this.fileSystemUtils = fileSystemUtils;
    }

    public GitRepo create(String repoTarget, GitProvider gitProvider) {
        return new GitRepo(config, gitProvider, repoTarget, fileSystemUtils);
    }
}
