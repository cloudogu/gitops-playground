package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.git.providers.GitProvider

class RepoInitializer {

    private final Config config
    private final GitRepoFactory repoFactory
    private final GitHandler gitHandler

    RepoInitializer(Config config,
                    GitRepoFactory repoFactory,
                    GitHandler gitHandler) {
        this.config = config
        this.repoFactory = repoFactory
        this.gitHandler = gitHandler
    }

    RepoContext initRepos() {
        RepoContext ctx = new RepoContext()

        if (!config.multiTenant.useDedicatedInstance) {
            // Single-Instance: single cluster-resources repo (tenant provider)
            def clusterResourcesRepo = createRepoInitializationAction(
                    'argocd/cluster-resources',
                    'argocd/cluster-resources',
                    gitHandler.tenant
            )
            ctx.addClusterResources(clusterResourcesRepo)
        } else {
            // Dedicated: a tenant bootstrap repo and a centralized cluster-resources repo

            def tenantBootstrap = createRepoInitializationAction(
                    'argocd/cluster-resources/apps/argocd/multiTenant/tenant',
                    'argocd/cluster-resources',
                    gitHandler.tenant
            )
            ctx.addTenantBootstrap(tenantBootstrap)

            def cluster = createRepoInitializationAction(
                    'argocd/cluster-resources',
                    'argocd/cluster-resources',
                    gitHandler.central
            )
            ctx.addClusterResources(cluster)
        }

        ctx.clusterResources.subDirsToCopy = determineClusterResourceSubDirs()

        return ctx
    }

    private Set<String> determineClusterResourceSubDirs() {
        Set<String> clusterResourceSubDirs = new LinkedHashSet<>()

        clusterResourceSubDirs.add(RepoLayout.argocdSubdirRel())

        if (config.features.certManager.active) {
            clusterResourceSubDirs.add(RepoLayout.certManagerSubdirRel())
        }
        if (config.features.ingressNginx.active) {
            clusterResourceSubDirs.add(RepoLayout.ingressSubdirRel())
        }
        if (config.jenkins.active) {
            clusterResourceSubDirs.add(RepoLayout.jenkinsSubdirRel())
        }
        if (config.features.mail.active) {
            clusterResourceSubDirs.add(RepoLayout.mailhogSubdirRel())
        }
        if (config.features.monitoring.active) {
            clusterResourceSubDirs.add(RepoLayout.monitoringSubdirRel())
        }
        if (config.scm.scmManager?.url) {
            clusterResourceSubDirs.add(RepoLayout.scmManagerSubdirRel())
        }
        if (config.features.secrets.active) {
            clusterResourceSubDirs.add(RepoLayout.secretsSubdirRel())
            clusterResourceSubDirs.add(RepoLayout.vaultSubdirRel())
        }

        return clusterResourceSubDirs
    }

    private RepoInitializationAction createRepoInitializationAction(String localSrcDir,
                                                                    String scmRepoTarget,
                                                                    GitProvider gitProvider) {
        new RepoInitializationAction(
                config,
                repoFactory.getRepo(scmRepoTarget, gitProvider),
                gitHandler,
                localSrcDir
        )
    }
}
