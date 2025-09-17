package com.cloudogu.gitops.gitHandling.providers.gitlab

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitHandling.providers.GitProvider
import com.cloudogu.gitops.gitHandling.providers.GitPushAuth
import com.cloudogu.gitops.gitHandling.providers.Permission
import jakarta.inject.Named


@Named("gitlab")
class GitlabProvider implements GitProvider {
    private final Config config

    GitlabProvider(Config config){
        this.config = config
    }

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        return false;
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {

    }

    @Override
    String computePushUrl(String repoTarget) {
        return "";
    }

    @Override
    GitPushAuth pushAuth(boolean isCentralRepo) {
        return new GitPushAuth("oauth2", config.scmm.password as String) // token in password
    }
}