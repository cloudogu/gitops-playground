package com.cloudogu.gitops.gitabstraction.serverOps

import com.cloudogu.gitops.scmm.api.Permission


/** Server-Ops:  easy overloads with defaults
 *  Server-Ops are actions performed against the remote SCM server (e.g., SCM-Manager, GitLab)
 *  via their HTTP/REST APIs.
 *  These operations change the server-side state and do not require a local Git checkout.
 * */
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
