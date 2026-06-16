package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider

import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@CompileStatic
class RepositoryProvisioning {

	static final String CLUSTER_RESOURCES_REPO_TARGET = 'argocd/cluster-resources'

	private final Config config
	private final GitRepoFactory gitRepoFactory
	private final GitHandler gitHandler

	private RepositoryWorkspace workspace
	private boolean remoteRepositoriesEnsured = false
	private boolean repositoriesCloned = false

	RepositoryProvisioning(Config config,
		GitRepoFactory gitRepoFactory,
		GitHandler gitHandler) {
		this.config = config
		this.gitRepoFactory = gitRepoFactory
		this.gitHandler = gitHandler
	}

	void prepare() {
		provideWorkspace()

		if (mustWaitForInternalScmManagerDeployment()) {
			log.debug('Preparing local repository workspace only because internal SCM-Manager is not deployed yet.')
			workspace.prepareLocalDirectories()
			return
		}

		ensureRemoteRepositoriesExist()
		cloneRepositories()
	}

	RepositoryWorkspace provideWorkspace() {
		if (workspace != null) {
			return workspace
		}

		if (config.multiTenant.useDedicatedInstance) {
			workspace = workspaceForDedicatedInstance()
		} else {
			workspace = workspaceForSingleInstance()
		}

		return workspace
	}

	void ensureRemoteRepositoriesExist() {
		if (remoteRepositoriesEnsured) {
			log.debug('Remote repositories already ensured. Skipping.')
			return
		}

		if (workspace == null) {
			throw new IllegalStateException('Repository workspace must be prepared before remote repositories can be ensured.')
		}

		ensureRepositoryExists(workspace.clusterResourcesRepo.gitProvider,
			clusterResourcesRepoTarget(),
			'GitOps repo for cluster resources')

		if (workspace.hasTenantBootstrapRepo()) {
			ensureRepositoryExists(workspace.tenantBootstrapRepoOrFail().gitProvider,
				clusterResourcesRepoTarget(),
				'GitOps repo for tenant bootstrap resources')
		}

		remoteRepositoriesEnsured = true
	}

	void cloneRepositories() {
		if (repositoriesCloned) {
			log.debug('Repositories already cloned. Skipping.')
			return
		}

		if (workspace == null) {
			throw new IllegalStateException('Repository workspace must be prepared before repositories can be cloned.')
		}

		workspace.cloneRepositories()
		repositoriesCloned = true
	}

	void publishInitialStateAfterScmManagerDeployment() {
		ensureRemoteRepositoriesExist()

		if (workspace == null) {
			throw new IllegalStateException('Repository workspace must be prepared before initial state can be published.')
		}

		workspace.initLocalRepositoriesIfNeeded()

		workspace.commitAndPushClusterChanges('Initial repository state with SCM-Manager resources')

		if (workspace.hasTenantBootstrapRepo()) {
			workspace.commitAndPushTenantBootstrapChanges('Initial tenant bootstrap repository state')
		}
	}

	void publishClusterChanges(String toolName, String message = null) {
		assertWorkspacePrepared()
		String commitMessage = message ?: "Update ${toolName} resources".toString()
		workspace.commitAndPushClusterChanges(commitMessage)
	}

	void publishAllWorkspaceChanges(String toolName, String message = null) {
		assertWorkspacePrepared()
		String commitMessage = message ?: "Update ${toolName} resources".toString()
		workspace.commitAndPushAllChanges(commitMessage)
	}

	String clusterResourcesRepoTarget() {
		return withOrgPrefix(namePrefix(), CLUSTER_RESOURCES_REPO_TARGET)
	}

	private static String withOrgPrefix(String prefix, String repoPath) {
		if (!prefix) {
			return repoPath
		}

		return prefix + repoPath
	}

	private static void ensureRepositoryExists(GitProvider gitProvider,
		String repoTarget,
		String description) {
		gitProvider.createRepository(repoTarget, description, true)
	}

	private void assertWorkspacePrepared() {
		if (workspace == null) {
			throw new IllegalStateException('Repository workspace must be prepared before changes can be published.')
		}
	}

	private RepositoryWorkspace workspaceForSingleInstance() {
		log.debug('Creating single-instance repository workspace.')

		GitRepo clusterResourcesRepo = gitRepoFactory.create(clusterResourcesRepoTarget(),
			gitHandler.tenant)

		return new RepositoryWorkspace(clusterResourcesRepo)
	}

	private RepositoryWorkspace workspaceForDedicatedInstance() {
		log.debug('Creating dedicated-instance repository workspace.')

		GitRepo centralClusterResourcesRepo = gitRepoFactory.create(clusterResourcesRepoTarget(),
			gitHandler.central)

		GitRepo tenantBootstrapRepo = gitRepoFactory.create(clusterResourcesRepoTarget(),
			gitHandler.tenant)

		return new RepositoryWorkspace(centralClusterResourcesRepo,
			tenantBootstrapRepo)
	}

	private String namePrefix() {
		return (config?.application?.namePrefix ?: '').trim()
	}

	private boolean mustWaitForInternalScmManagerDeployment() {
		return config.scm.scmProviderType == ScmProviderType.SCM_MANAGER && config.scm.scmManager?.internal
	}

}