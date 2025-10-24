package com.cloudogu.gitops.utils.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider

class TestGitProvider {
    static Map<String, GitProvider> buildProviders(Config cfg) {
        if (cfg.scm.scmProviderType?.toString() == 'GITLAB') {
            def gitlab = new GitlabMock(
                    base: new URI(cfg.scm.gitlab.url),
                    namePrefix: cfg.application.namePrefix
            )
            return [tenant: gitlab, central: cfg.multiTenant.useDedicatedInstance ? gitlab : null]
        }

        def serviceDns = "http://scmm.${cfg.application.namePrefix}scm-manager.svc.cluster.local/scm"
        String tenantInCluster  = (cfg.scm.scmManager?.url ?: serviceDns) as String
        String centralInCluster = (cfg.multiTenant.scmManager?.url ?: tenantInCluster) as String

        def tenant  = new ScmManagerMock(inClusterBase: new URI(tenantInCluster),  namePrefix: cfg.application.namePrefix)
        def central = cfg.multiTenant.useDedicatedInstance
                ? new ScmManagerMock(inClusterBase: new URI(centralInCluster), namePrefix: cfg.application.namePrefix)
                : null
        return [tenant: tenant, central: central]
    }
}



