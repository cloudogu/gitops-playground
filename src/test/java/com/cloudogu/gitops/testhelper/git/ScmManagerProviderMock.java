package com.cloudogu.gitops.testhelper.git;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight test double for SCM-Manager via the GitProvider interface.
 * <p>
 * Models the SCM-Manager specific GitProvider behavior that is relevant for tests:
 * - configurable in-cluster and client base URLs
 * - optional namePrefix to model tenant behavior
 * - repository URL/prefix generation
 * - createRepository/setRepositoryPermission call recording
 */
public class ScmManagerProviderMock implements GitProvider {

	private final Set<String> initOnceRepos = new HashSet<>();
	private final Map<String, Integer> createCalls = new HashMap<>();

	@Getter
	@Setter
	private URI inClusterBase = URI.create("http://scmm.scm-manager.svc.cluster.local/scm");

	@Getter
	@Setter
	private URI clientBase = URI.create("http://localhost:8080/scm");

	@Getter
	@Setter
	private String namePrefix = "";

	@Setter
	private Credentials credentials = new Credentials("gitops", "gitops");

	@Setter
	private String gitOpsUsername = "gitops";

	@Getter
	@Setter
	private URI prometheus = URI.create("http://localhost:8080/scm/api/v2/metrics/prometheus");

	@Getter
	private final List<String> createdRepos = new ArrayList<>();

	@Getter
	private final List<Map<String, Object>> permissionCalls = new ArrayList<>();

	/**
	 * Optional sequence to control createRepository() return values per call.
	 * <p>
	 * Empty list means: return true by default.
	 */
	@Getter
	@Setter
	private List<Boolean> nextCreateResults = new ArrayList<>();

	public void initOnceRepo(String fullName) {
		initOnceRepos.add(fullName);
	}

	public void clearInitOnce() {
		initOnceRepos.clear();
		createCalls.clear();
	}

	@Override
	public boolean createRepository(String repoTarget, String description, boolean initialize) {
		createdRepos.add(repoTarget);

		if (initOnceRepos.contains(repoTarget)) {
			return createCalls.merge(repoTarget, 1, Integer::sum) == 1;
		}

		return nextCreateResults == null || nextCreateResults.isEmpty() ? true : nextCreateResults.remove(0);
	}

	@Override
	public void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
		Map<String, Object> permissionCall = new LinkedHashMap<>();
		permissionCall.put("repoTarget", repoTarget);
		permissionCall.put("principal", principal);
		permissionCall.put("role", role);
		permissionCall.put("scope", scope);
		permissionCalls.add(permissionCall);
	}

	@Override
	public String repoUrl(String repoTarget, RepoUrlScope scope) {
		URI base = scope == RepoUrlScope.CLIENT ? clientBase : inClusterBase;
		String cleanedBase = withoutTrailingSlash(base).toString();
		return cleanedBase + "/repo/" + repoTarget;
	}

	@Override
	public String repoPrefix() {
		String base = withoutTrailingSlash(inClusterBase).toString();
		String prefix = namePrefix == null ? "" : namePrefix;
		return base + "/repo/" + prefix;
	}

	@Override
	public Credentials getCredentials() {
		return credentials;
	}

	@Override
	public URI prometheusMetricsEndpoint() {
		return prometheus;
	}

	@Override
	public String getUrl() {
		return withoutTrailingSlash(inClusterBase).toString();
	}

	@Override
	public String getProtocol() {
		return inClusterBase.getScheme();
	}

	@Override
	public String getHost() {
		return inClusterBase.getHost();
	}

	@Override
	public String getGitOpsUsername() {
		return gitOpsUsername;
	}

	private static URI withoutTrailingSlash(URI uri) {
		String value = uri.toString();
		return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
	}
}
