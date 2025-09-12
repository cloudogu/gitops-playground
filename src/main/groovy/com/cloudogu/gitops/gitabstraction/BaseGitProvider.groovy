package com.cloudogu.gitops.gitabstraction

import com.cloudogu.gitops.scmm.api.Permission


/** Easy overloads with defaults */
abstract class BaseGitProvider implements GitProvider {
    boolean createRepository(String repoTarget) {
        return createRepository(repoTarget, "", true)
    }

    boolean createRepository(String repoTarget, String description) {
        return createRepository(repoTarget, description, true)
    }

    //TODO check the needed default groupPermission for gitlab and scm-manager, if there are differences, make it configurable
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role) {
        setRepositoryPermission(repoTarget, principal, role, false)
    }
}
