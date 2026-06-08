package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

@Slf4j
@Singleton
class RepositoryProvisioning {

    static final String CLUSTER_RESOURCES_REPO_TARGET = 'argocd/cluster-resources'

    private final Config config
    private final GitRepoFactory gitRepoFactory
    private final GitHandler gitHandler

    private RepositoryWorkspace workspace
    private boolean remoteRepositoriesEnsured = false

    RepositoryProvisioning(
            Config config,
            GitRepoFactory gitRepoFactory,
            GitHandler gitHandler
    ) {
        this.config = config
        this.gitRepoFactory = gitRepoFactory
        this.gitHandler = gitHandler
    }

    void prepare() {
        provideWorkspace()

        if (mustWaitForInternalScmManagerDeployment()) {
            log.debug('Skipping remote repository provisioning until internal SCM-Manager is deployed.')
            return
        }

        ensureRemoteRepositoriesExist()
    }

    RepositoryWorkspace provideWorkspace() {
        if (workspace != null) {
            return workspace
        }

        if (config.multiTenant.useDedicatedInstance) {
            workspace = createDedicatedInstanceWorkspace()
        } else {
            workspace = createSingleInstanceWorkspace()
        }

        workspace.prepareLocalDirectories()

        return workspace
    }

    void ensureRemoteRepositoriesExist() {
        if (remoteRepositoriesEnsured) {
            log.debug('Remote repositories already ensured. Skipping.')
            return
        }

        if (workspace == null) {
            throw new IllegalStateException(
                    'Repository workspace must be prepared before remote repositories can be ensured.'
            )
        }

        ensureRepositoryExists(
                workspace.clusterResourcesRepo.gitProvider,
                clusterResourcesRepoTarget(),
                'GitOps repo for cluster resources'
        )

        if (workspace.hasTenantBootstrapRepo()) {
            ensureRepositoryExists(
                    workspace.tenantBootstrapRepoOrFail().gitProvider,
                    clusterResourcesRepoTarget(),
                    'GitOps repo for tenant bootstrap resources'
            )
        }

        remoteRepositoriesEnsured = true
    }

    void publishInitialStateAfterScmManagerDeployment() {
        ensureRemoteRepositoriesExist()

        if (workspace == null) {
            throw new IllegalStateException(
                    'Repository workspace must be prepared before initial state can be published.'
            )
        }

        workspace.commitAndPushClusterChanges(
                'Initial repository state with SCM-Manager resources'
        )

        if (workspace.hasTenantBootstrapRepo()) {
            workspace.commitAndPushTenantBootstrapChanges(
                    'Initial tenant bootstrap repository state'
            )
        }
    }

    void commitAndPushToolChanges(String toolName, String message = null) {
        if (workspace == null) {
            throw new IllegalStateException(
                    'Repository workspace must be prepared before tool changes can be published.'
            )
        }

        workspace.commitAndPushClusterChanges(
                message ?: "Update ${toolName} resources"
        )
    }

    void commitAndPushAllToolChanges(String toolName, String message = null) {
        if (workspace == null) {
            throw new IllegalStateException(
                    'Repository workspace must be prepared before tool changes can be published.'
            )
        }

        workspace.commitAndPushAllChanges(
                message ?: "Update ${toolName} resources"
        )
    }

    String clusterResourcesRepoTarget() {
        withOrgPrefix(namePrefix(), CLUSTER_RESOURCES_REPO_TARGET)
    }

    private RepositoryWorkspace createSingleInstanceWorkspace() {
        log.debug('Preparing local single-instance repository workspace.')

        GitRepo clusterResourcesRepo = gitRepoFactory.getRepo(
                clusterResourcesRepoTarget(),
                gitHandler.tenant
        )

        return new RepositoryWorkspace(clusterResourcesRepo)
    }

    private RepositoryWorkspace createDedicatedInstanceWorkspace() {
        log.debug('Preparing local dedicated-instance repository workspace.')

        GitRepo centralClusterResourcesRepo = gitRepoFactory.getRepo(
                clusterResourcesRepoTarget(),
                gitHandler.central
        )

        GitRepo tenantBootstrapRepo = gitRepoFactory.getRepo(
                clusterResourcesRepoTarget(),
                gitHandler.tenant
        )

        return new RepositoryWorkspace(
                centralClusterResourcesRepo,
                tenantBootstrapRepo
        )
    }

    private String namePrefix() {
        (config?.application?.namePrefix ?: '').trim()
    }

    private static String withOrgPrefix(String prefix, String repoPath) {
        if (!prefix) {
            return repoPath
        }

        prefix + repoPath
    }

    private boolean mustWaitForInternalScmManagerDeployment() {
        config.scm.scmProviderType == ScmProviderType.SCM_MANAGER &&
                config.scm.scmManager?.internal
    }

    private static void ensureRepositoryExists(
            GitProvider gitProvider,
            String repoTarget,
            String description
    ) {
        gitProvider.createRepository(repoTarget, description, true)
    }
}