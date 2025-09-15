package com.cloudogu.gitops.gitHandling.gitServerClients

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.api.ScmmApiClient

//TODO as enum
class GitProviderFactory {
    static GitProvider create(Config config, ScmmApiClient scmmApiClient) {
        switch ((config.scmm.provider ?: "scm-manager").toLowerCase()) {
            case "scm-manager":
                return new ScmManagerGitProvider(config, scmmApiClient)
            case "gitlab":
                return new GitlabGitProvider(config)
            default:
                throw new IllegalArgumentException("Unknown SCM provider: ${config.scmm.provider}")
        }
    }
}
