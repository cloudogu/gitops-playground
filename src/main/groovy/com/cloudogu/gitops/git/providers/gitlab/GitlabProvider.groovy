package com.cloudogu.gitops.git.providers.gitlab

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.GitPushAuth
import com.cloudogu.gitops.git.providers.Permission
import jakarta.inject.Named


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

    //TODO implement
    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {

    }

    //TODO implement
    @Override
    void deleteUser(String name) {

    }

    //TODO implement
    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }
}