package com.cloudogu.gitops.gitHandling.gitServerClients

import com.cloudogu.gitops.config.Config

class GitlabGitProvider implements GitProvider {
    private final Config config

    GitlabGitProvider (Config config){
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