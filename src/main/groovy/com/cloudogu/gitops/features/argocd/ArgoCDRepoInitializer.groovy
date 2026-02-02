package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.git.providers.GitProvider

class ArgoCDRepoInitializer {

    private final Config config
    private final GitRepoFactory repoFactory
    private final GitHandler gitHandler

    ArgoCDRepoInitializer(Config config,
                          GitRepoFactory repoFactory,
                          GitHandler gitHandler) {
        this.config = config
        this.repoFactory = repoFactory
        this.gitHandler = gitHandler
    }

    ArgoCDRepoContext initRepos() {
        ArgoCDRepoContext ctx = new ArgoCDRepoContext()

        if (!config.multiTenant.useDedicatedInstance) {
            // Single-Instance: nur ein cluster-resources Repo (Tenant-Provider)
            def cluster = createRepoInitializationAction(
                    'argocd/cluster-resources',
                    'argocd/cluster-resources',
                    gitHandler.tenant
            )
            ctx.addClusterResources(cluster)
        } else {
            // Dedicated: Tenant-Bootstrap-Repo + zentrales cluster-resources Repo

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

        // welche Subdirs werden überhaupt nach cluster-resources gespiegelt?
        ctx.clusterResources.subDirsToCopy = determineClusterResourceSubDirs()

        return ctx
    }

    private Set<String> determineClusterResourceSubDirs() {
        Set<String> clusterResourceSubDirs = new LinkedHashSet<>()

        clusterResourceSubDirs.add(ArgoCDRepoLayout.argocdSubdirRel())

        if (config.features.certManager.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.certManagerSubdirRel())
        }
        if (config.features.ingressNginx.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.ingressSubdirRel())
        }
        if (config.jenkins.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.jenkinsSubdirRel())
        }
        if (config.features.mail.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.mailhogSubdirRel())
        }
        if (config.features.monitoring.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.monitoringSubdirRel())
        }
        if (config.scm.scmManager?.url) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.scmManagerSubdirRel())
        }
        if (config.features.secrets.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.secretsSubdirRel())
            clusterResourceSubDirs.add(ArgoCDRepoLayout.vaultSubdirRel())
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
