package com.cloudogu.gitops.gitabstraction


/** Bequeme Overloads mit Defaults */
abstract class BaseGitProvider implements GitProvider {
    boolean createRepository(String repoTarget) {
        return createRepository(repoTarget, "", true)
    }

    boolean createRepository(String repoTarget, String description) {
        return createRepository(repoTarget, description, true)
    }

    void setDefaultBranch(String repoTarget) {
        setDefaultBranch(repoTarget, "main")
    }

    void setRepositoryPermission(String repoTarget, String principal, String role) {
        setRepositoryPermission(repoTarget, principal, role, false)
    }
}
