package com.cloudogu.gitops.gitabstraction

class ScmGitProvider extends BaseGitProvider implements GitProvider{

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        return false
    }

    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, String role, boolean groupPermission) {

    }

    @Override
    String computePushUrl(String repoTarget) {
        return null
    }
}
