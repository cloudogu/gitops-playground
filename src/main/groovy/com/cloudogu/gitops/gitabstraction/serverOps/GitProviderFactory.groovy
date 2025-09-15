package com.cloudogu.gitops.gitabstraction.serverOps

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.api.ScmmApiClient

class GitProviderFactory {
    static GitProvider create(Config config, ScmmApiClient scmmApiClient) {
        switch ((config.scmm.provider ?: "scm-manager").toLowerCase()) {
            case "scm-manager":
                return new ScmGitProvider(config, scmmApiClient)
            case "gitlab":
                return new GitlabGitProvider(config)
            default:
                throw new IllegalArgumentException("Unknown SCM provider: ${config.scmm.provider}")
        }
    }
}
