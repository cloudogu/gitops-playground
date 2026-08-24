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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GitlabMock implements GitProvider {

	@Getter
	@Setter
	private URI base = URI.create("https://example.com/group");

	@Getter
	@Setter
	private String namePrefix = "";

	@Getter
	private final List<String> createdRepos = new ArrayList<>();

	@Getter
	private final List<Map<String, Object>> permissionCalls = new ArrayList<>();

	@Override
	public boolean createRepository(String repoTarget, String description, boolean initialize) {
		createdRepos.add(repoTarget);
		return true;
	}

	@Override
	public boolean createRepository(String repoTarget, String description) {
		return createRepository(repoTarget, description, true);
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
		String cleaned = base.toString().replaceAll("/+$", "");
		return cleaned + "/" + repoTarget + ".git";
	}

	@Override
	public String repoPrefix() {
		String cleaned = base.toString().replaceAll("/+$", "");
		String prefix = namePrefix == null ? "" : namePrefix;
		return cleaned + "/" + prefix;
	}

	@Override
	public URI prometheusMetricsEndpoint() {
		return base;
	}

	@Override
	public Credentials getCredentials() {
		return new Credentials("gitops", "gitops");
	}

	@Override
	public String getUrl() {
		return base.toString();
	}

	@Override
	public String getProtocol() {
		return base.getScheme();
	}

	@Override
	public String getHost() {
		return base.getHost();
	}

	@Override
	public String getGitOpsUsername() {
		return "gitops";
	}
}
