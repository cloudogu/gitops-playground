package com.cloudogu.gitops.utils.git

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope


/**
 * Lightweight test double for ScmManager/GitProvider.
 * - Configurable in-cluster and client bases
 * - Optional namePrefix to model “tenant” behavior
 * - Records createRepository / setRepositoryPermission calls for assertions
 */
class ScmManagerMock implements GitProvider {

    private final Set<String> initOnceRepos = [] as Set
    private final Map<String,Integer> createCalls = [:].withDefault{0}

    void initOnceRepo(String fullName) { initOnceRepos << fullName }
    void clearInitOnce() { initOnceRepos.clear(); createCalls.clear() }


    // --- configurable  ---
    URI inClusterBase = new URI("http://scmm.scm-manager.svc.cluster.local/scm")
    URI clientBase = new URI("http://localhost:8080/scm")
    String rootPath = "repo"            // SCMM rootPath
    String namePrefix = ""                // e.g., "fv40-" for tenant mode
    Credentials credentials = new Credentials("gitops", "gitops")
    String gitOpsUsername = "gitops"
    URI prometheus = new URI("http://localhost:8080/scm/api/v2/metrics/prometheus")

    // --- call recordings for assertions ---
    final List<String> createdRepos = []
    final List<Map> permissionCalls = []
    /** Optional sequence to control createRepository() return values per call */
    List<Boolean> nextCreateResults = []  // empty -> default true

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        if (initOnceRepos.contains(repoTarget)) {
            return ++createCalls[repoTarget] == 1   // 1. call true, then false
        }
        createdRepos << repoTarget
        // Pretend repository was created successfully.
        // If you need idempotency checks, examine createdRepos.count(repoTarget) in your tests.
        return nextCreateResults ? nextCreateResults.remove(0) : true
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        permissionCalls << [
                repoTarget: repoTarget,
                principal : principal,
                role      : role,
                scope     : scope
        ]
    }

    /** …/scm/<rootPath>/<ns>/<name> */
    @Override
    String repoUrl(String repoTarget, RepoUrlScope scope) {
        URI base = (scope == RepoUrlScope.CLIENT) ? clientBase : inClusterBase
        def cleanedBase = withoutTrailingSlash(base).toString()
        return "${cleanedBase}/${rootPath}/${repoTarget}"
    }

    /** In-cluster repo prefix: …/scm/<rootPath>/[<namePrefix>] */
    @Override
    String repoPrefix() {
        def base = withoutTrailingSlash(inClusterBase).toString()
        def prefix = (namePrefix ?: "").strip()
        return "${base}/${rootPath}/${prefix}"
    }

    @Override
    Credentials getCredentials() {
        return credentials
    }


    /** …/scm/api/v2/metrics/prometheus */
    @Override
    URI prometheusMetricsEndpoint() {
        return prometheus
    }

    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {

    }

    @Override
    void deleteUser(String name) {

    }

    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }

    /** In-cluster base …/scm (without trailing slash) */
    @Override
    String getUrl() {
        return inClusterBase.toString()
    }

    @Override
    String getProtocol() {
        return inClusterBase.scheme  // e.g., "http"
    }

    @Override
    String getHost() {
        return inClusterBase.host    // e.g., "scmm.ns.svc.cluster.local"
    }

    @Override
    String getGitOpsUsername() {
        return gitOpsUsername
    }
    // --- helpers ---
    private static URI withoutTrailingSlash(URI uri) {
        def s = uri.toString()
        return new URI(s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
    }
}
