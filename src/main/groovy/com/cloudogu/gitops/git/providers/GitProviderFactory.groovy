package com.cloudogu.gitops.git.providers

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.util.ScmmConfig
import com.cloudogu.gitops.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient

//TODO as enum

class GitProviderFactory {
    // take provider from config
    static GitProvider fromConfig(Config config, Deps deps) {
        switch ((config?.scmm.provider ?: "scm-manager").toLowerCase()) {
            case "scm-manager":
                require(deps.scmmApiClient, "ScmmApiClient is missing for scm-manager")
                return new ScmManagerProvider(config as ScmmConfig, deps.scmmApiClient)
            case "gitlab":
//                require(deps.gitlabApiClient, "GitlabApiClient is missing for  gitlab")
//                return new GitlabGitProvider(config, deps.gitlabApiClient)
                return null
            default:
                throw new IllegalStateException("Unbekannter scmm.provider: ${config?.scmm?.provider}")
        }
    }

    static class Deps {
        ScmManagerApiClient scmmApiClient
        //TODO add  GitlabApiClient
    }

    private static void require(Object object, String msg) {
        if (object == null) throw new IllegalStateException(msg)
    }
}
