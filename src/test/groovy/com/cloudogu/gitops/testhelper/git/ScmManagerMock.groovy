package com.cloudogu.gitops.testhelper.git

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope
import com.cloudogu.gitops.infrastructure.git.providers.Scope

/**
 * Lightweight test double for ScmManagerProvider via the GitProvider interface.
 *
 * Models the SCM-Manager specific GitProvider behavior that is relevant for tests:
 * - configurable in-cluster and client base URLs
 * - optional namePrefix to model tenant behavior
 * - repository URL/prefix generation
 * - createRepository/setRepositoryPermission call recording*/
class ScmManagerMock implements GitProvider {

	private final Set<String> initOnceRepos = [] as Set
	private final Map<String, Integer> createCalls = [:].withDefault { 0 }

	void initOnceRepo(String fullName) {
		initOnceRepos << fullName
	}

	void clearInitOnce() {
		initOnceRepos.clear()
		createCalls.clear()
	}

	// --- configurable ---
	URI inClusterBase = new URI('http://scmm.scm-manager.svc.cluster.local/scm')
	URI clientBase = new URI('http://localhost:8080/scm')
	String namePrefix = ''
	Credentials credentials = new Credentials('gitops', "gitops")
	String gitOpsUsername = 'gitops'
	URI prometheus = new URI('http://localhost:8080/scm/api/v2/metrics/prometheus')

	// --- call recordings for assertions ---
	final List<String> createdRepos = []
	final List<Map> permissionCalls = []

	/**
	 * Optional sequence to control createRepository() return values per call.
	 *
	 * Empty list means: return true by default.	*/
	List<Boolean> nextCreateResults = []

	@Override
	boolean createRepository(String repoTarget, String description, boolean initialize) {
		createdRepos << repoTarget

		if (initOnceRepos.contains(repoTarget)) {
			return ++createCalls[repoTarget] == 1
		}

		return nextCreateResults ? nextCreateResults.remove(0) : true
	}

	@Override
	void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
		permissionCalls << [repoTarget: repoTarget,
		                    principal : principal,
		                    role      : role,
		                    scope     : scope]
	}

	/**
	 * Builds a repository URL like:
	 * .../scm/repo/<namespace>/<repository>	*/
	@Override
	String repoUrl(String repoTarget, RepoUrlScope scope) {
		URI base = scope == RepoUrlScope.CLIENT ? clientBase : inClusterBase
		String cleanedBase = withoutTrailingSlash(base).toString()

		return "${cleanedBase}/repo/${repoTarget}"
	}

	/**
	 * Builds the in-cluster repository prefix like:
	 * .../scm/repo/<namePrefix>	*/
	@Override
	String repoPrefix() {
		String base = withoutTrailingSlash(inClusterBase).toString()
		String prefix = namePrefix ?: ''

		return "${base}/repo/${prefix}"
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

	/** In-cluster base …/scm (without trailing slash) */
	@Override
	String getUrl() {
		return withoutTrailingSlash(inClusterBase).toString()
	}

	@Override
	String getProtocol() {
		return inClusterBase.scheme
	}

	@Override
	String getHost() {
		return inClusterBase.host
	}

	@Override
	String getGitOpsUsername() {
		return gitOpsUsername
	}

	private static URI withoutTrailingSlash(URI uri) {
		String s = uri.toString()
		return new URI(s.endsWith('/') ? s.substring(0, s.length() - 1) : s)
	}
}