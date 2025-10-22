package com.cloudogu.gitops.git.providers.gitlab

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope

class GitlabMock implements GitProvider {
    URI base = new URI("https://example.com/group") // from config.scm.gitlab.url
    String namePrefix = ""                          // prefix if you use tenant mode

    final List<String> createdRepos = []
    final List<Map> permissionCalls = []

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        createdRepos << withPrefix(repoTarget)
        return true
    }

    @Override
    boolean createRepository(String repoTarget, String description) {
        return createRepository(repoTarget, description, true)
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        permissionCalls << [repoTarget: withPrefix(repoTarget), principal: principal, role: role, scope: scope]
    }

    @Override
    String repoUrl(String repoTarget, RepoUrlScope scope) {
        def cleaned = base.toString().replaceAll('/+$','')
        return "${cleaned}/${withPrefix(repoTarget)}.git"
    }


    @Override
    String repoPrefix() {
        def cleaned = base.toString().replaceAll('/+$','')
        return "${cleaned}/${namePrefix?:''}".toString()
    }

    // trivial passthroughs
    @Override URI prometheusMetricsEndpoint() { return base }
    @Override Credentials getCredentials() { return new Credentials("gitops","gitops") }
    @Override void deleteRepository(String n, String r, boolean p) {}
    @Override void deleteUser(String name) {}
    @Override void setDefaultBranch(String target, String branch) {}
    @Override String getUrl() { return base.toString() }
    @Override String getProtocol() { return base.scheme }
    @Override String getHost() { return base.host }
    @Override String getGitOpsUsername() { return "gitops" }

    private String withPrefix(String target) {
        return (namePrefix ? "${namePrefix}${target}" : target).toString()
    }

}
