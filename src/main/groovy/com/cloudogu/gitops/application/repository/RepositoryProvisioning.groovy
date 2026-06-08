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
            throw new IllegalStateException('Repository workspace must be provided before remote repositories can be ensured.')
        }

        ensureRepositoryExists(
                workspace.clusterResourcesRepo.gitProvider,
                CLUSTER_RESOURCES_REPO_TARGET,
                'GitOps repo for cluster resources'
        )

        if (workspace.hasTenantBootstrapRepo()) {
            ensureRepositoryExists(
                    workspace.tenantBootstrapRepoOrFail().gitProvider,
                    CLUSTER_RESOURCES_REPO_TARGET,
                    'GitOps repo for tenant bootstrap resources'
            )
        }

        remoteRepositoriesEnsured = true
    }

    void publishInitialStateAfterScmManagerDeployment() {
        ensureRemoteRepositoriesExist()

        RepositoryWorkspace currentWorkspace = provideWorkspace()

        currentWorkspace.commitAndPushClusterChanges(
                'Initial repository state with SCM-Manager resources'
        )

        if (currentWorkspace.hasTenantBootstrapRepo()) {
            currentWorkspace.commitAndPushTenantBootstrapChanges(
                    'Initial tenant bootstrap repository state'
            )
        }
    }

    void commitAndPushToolChanges(String toolName, String message = null) {
        RepositoryWorkspace currentWorkspace = provideWorkspace()

        currentWorkspace.commitAndPushClusterChanges(
                message ?: "Update ${toolName} resources"
        )
    }

    void commitAndPushAllToolChanges(String toolName, String message = null) {
        RepositoryWorkspace currentWorkspace = provideWorkspace()

        currentWorkspace.commitAndPushAllChanges(
                message ?: "Update ${toolName} resources"
        )
    }

    private RepositoryWorkspace createSingleInstanceWorkspace() {
        log.debug('Preparing local single-instance repository workspace.')

        GitRepo clusterResourcesRepo = gitRepoFactory.getRepo(
                CLUSTER_RESOURCES_REPO_TARGET,
                gitHandler.tenant
        )

        return new RepositoryWorkspace(clusterResourcesRepo)
    }

    private RepositoryWorkspace createDedicatedInstanceWorkspace() {
        log.debug('Preparing local dedicated-instance repository workspace.')

        GitRepo centralClusterResourcesRepo = gitRepoFactory.getRepo(
                CLUSTER_RESOURCES_REPO_TARGET,
                gitHandler.central
        )

        GitRepo tenantBootstrapRepo = gitRepoFactory.getRepo(
                CLUSTER_RESOURCES_REPO_TARGET,
                gitHandler.tenant
        )

        return new RepositoryWorkspace(
                centralClusterResourcesRepo,
                tenantBootstrapRepo
        )
    }

    private boolean mustWaitForInternalScmManagerDeployment() {
        return config.scm.scmProviderType == ScmProviderType.SCM_MANAGER &&
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