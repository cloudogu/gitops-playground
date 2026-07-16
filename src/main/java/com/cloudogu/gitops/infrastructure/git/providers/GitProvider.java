package com.cloudogu.gitops.infrastructure.git.providers;

import com.cloudogu.gitops.config.Credentials;
import java.net.URI;

public interface GitProvider {

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
