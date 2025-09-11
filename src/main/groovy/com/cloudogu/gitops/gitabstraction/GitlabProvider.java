package com.cloudogu.gitops.gitabstraction;

import com.cloudogu.gitops.scmm.api.Permission;

public class GitlabProvider extends BaseGitProvider implements GitProvider {
    @Override
    public boolean createRepository(String repoTarget, String description, boolean initialize) {
        return false;
    }

    @Override
    public void setDefaultBranch(String repoTarget, String branch) {

    }

    @Override
    public void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {

    }


    @Override
    public String computePushUrl(String repoTarget) {
        return "";
    }
}
