package com.cloudogu.gitops.infrastructure.git.providers;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.utils.Tuple;

import java.net.URI;

public interface GitProvider {

	int REPO_TARGET_SEGMENT_COUNT = 2;

	/**
	 * Splits a "namespace/repoName" repo target into its namespace and name segments. Shared by
	 * providers that address repositories via a flat "namespace/name" string.
	 */
	static Tuple<String, String> splitRepoTarget(String repoTarget) {
		String[] parts = repoTarget.split("/", REPO_TARGET_SEGMENT_COUNT);
		return new Tuple<>(parts[0], parts[1]);
	}

	default boolean createRepository(String repoTarget, String description) {
		return createRepository(repoTarget, description, true);
	}

	boolean createRepository(String repoTarget, String description, boolean initialize);

	void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope);

	default String repoUrl(String repoTarget) {
		return repoUrl(repoTarget, RepoUrlScope.IN_CLUSTER);
	}

	String repoUrl(String repoTarget, RepoUrlScope scope);

	String repoPrefix();

	Credentials getCredentials();

	URI prometheusMetricsEndpoint();

	String getUrl();

	String getProtocol();

	String getHost();

	String getGitOpsUsername();
}
