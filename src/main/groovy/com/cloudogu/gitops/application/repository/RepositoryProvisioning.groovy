package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
class RepositoryProvisioning {

	static final String CLUSTER_RESOURCES_REPO_TARGET = 'argocd/cluster-resources'

	private final DeploymentContext context
	private final GitRepoFactory gitRepoFactory
	private final GitHandler gitHandler

	private RepositoryWorkspace workspace
	private boolean remoteRepositoriesEnsured = false
	private boolean repositoriesCloned = false

	RepositoryProvisioning(DeploymentContext context,
		GitRepoFactory gitRepoFactory,
		GitHandler gitHandler) {
		this.context = context
		this.gitRepoFactory = gitRepoFactory
		this.gitHandler = gitHandler
	}

	void prepare() {
		provideWorkspace()

		if (mustWaitForInternalScmManagerDeployment()) {
			log.info('Preparing local repository workspace only because internal SCM-Manager is not deployed yet.')
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

		if (context.isMultiTenant()) {
			workspace = createDedicatedInstanceWorkspace()
		} else {
			workspace = createSingleInstanceWorkspace()
		}

		return workspace
	}

	void ensureRemoteRepositoriesExist() {
		if (remoteRepositoriesEnsured) {
			log.debug('Remote repositories already ensured. Skipping.')
			return
		}

		assertWorkspacePrepared()

		log.debug("Ensuring cluster resources repository. repoTarget='{}'",
			workspace.clusterResourcesRepository.repoTarget)

		ensureRepositoryExists(workspace.clusterResourcesRepository.gitProvider,
			workspace.clusterResourcesRepository.repoTarget,
			'GitOps repo for basic cluster-resources')

		if (workspace.hasTenantBootstrapRepository()) {
			log.debug("Ensuring tenant bootstrap repository. repoTarget='{}'",
				workspace.tenantBootstrapRepositoryOrFail().repoTarget)

			ensureRepositoryExists(workspace.tenantBootstrapRepositoryOrFail().gitProvider,
				workspace.tenantBootstrapRepositoryOrFail().repoTarget,
				'GitOps repo for tenant bootstrap resources')
		}

		remoteRepositoriesEnsured = true
	}

	void cloneRepositories() {
		if (repositoriesCloned) {
			log.debug('Repositories already cloned. Skipping.')
			return
		}

		assertWorkspacePrepared()

		workspace.cloneRepositories()
		repositoriesCloned = true
	}

	void bootstrapRepositoriesAfterScmManagerDeployment() {
		ensureRemoteRepositoriesExist()

		assertWorkspacePrepared()

		workspace.initLocalRepositoriesIfNeeded()

		/*
		 * After the internal SCM-Manager has created the remote repositories,
		 * the remote main branch may already contain an initial commit, for example
		 * a README.md created by SCM-Manager.
		 *
		 * The locally initialized workspace must start from that remote main branch,
		 * otherwise the first push from GOP may be rejected as non-fast-forward.
		 */
		workspace.checkoutMainFromRemoteIfLocalMainMissing()
		workspace.prepareLocalDirectories()

		workspace.commitAndPushClusterResourcesChanges('Bootstrap cluster-resources repository after SCM-Manager deployment')

		if (workspace.hasTenantBootstrapRepository()) {
			workspace.commitAndPushTenantBootstrapChanges('Bootstrap tenant repository after SCM-Manager deployment')
		}
	}

	void publishClusterResourcesRepositoryChanges(String toolName,
		String message = null) {
		assertWorkspacePrepared()

		workspace.commitAndPushClusterResourcesChanges((message ?: "Update ${toolName} resources").toString())
	}

	void publishClusterResourcesAndTenantBootstrapRepositoryChanges(String toolName,
		String message = null) {
		assertWorkspacePrepared()

		workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges((message ?: "Update ${toolName} resources").toString())
	}

	String clusterResourcesRepoTarget() {
		// TODO: Move GOP-specific repo target prefixing from GitRepo to RepositoryProvisioning.
		// GitRepo currently applies context.config.application.namePrefix internally.
		// Therefore this method must return the unprefixed repository target for now.
		return CLUSTER_RESOURCES_REPO_TARGET
	}

	private RepositoryWorkspace createSingleInstanceWorkspace() {
		log.debug('Creating single-instance repository workspace.')

		GitRepo clusterResourcesRepository = gitRepoFactory.create(clusterResourcesRepoTarget(),
			gitHandler.getResourcesScm())

		return new RepositoryWorkspace(clusterResourcesRepository)
	}

	private RepositoryWorkspace createDedicatedInstanceWorkspace() {
		log.debug('Creating dedicated-instance repository workspace.')

		/*
		 * Dedicated Multi-Tenant mode:
		 *
		 * clusterResourcesRepository:
		 *   - points to the tenant cluster-resources repository in the central SCM-Manager
		 *   - is used by the central ArgoCD bootstrap
		 *
		 * tenantBootstrapRepository:
		 *   - points to the tenant cluster-resources repository in the tenant SCM-Manager
		 *   - contains the bootstrap resources for the tenant ArgoCD instance
		 *
		 * Both repositories use the same logical repo target, but they must not share the
		 * same local workspace. The central and tenant templates contain overlapping paths
		 * such as apps/argocd/applications/bootstrap.yaml.
		 */
		GitRepo clusterResourcesRepository = gitRepoFactory.create(clusterResourcesRepoTarget(),
			gitHandler.getResourcesScm())

		GitRepo tenantBootstrapRepository = gitRepoFactory.create(clusterResourcesRepoTarget(),
			gitHandler.tenant)

		RepositoryWorkspace dedicatedWorkspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		validateDedicatedWorkspace(dedicatedWorkspace)

		return dedicatedWorkspace
	}

	private static void validateDedicatedWorkspace(RepositoryWorkspace workspace) {
		String clusterRoot = new File(workspace.clusterResourcesRootDir()).canonicalPath
		String tenantRoot = new File(workspace.tenantBootstrapRootDir()).canonicalPath

		if (clusterRoot == tenantRoot) {
			throw new IllegalStateException("Dedicated Multi-Tenant mode requires separate local workspaces for " + "central cluster-resources and tenant bootstrap repositories. " +
				"Both resolved to: ${clusterRoot}")
		}
	}

	private void assertWorkspacePrepared() {
		if (workspace == null) {
			throw new IllegalStateException('Repository workspace must be prepared before repository changes can be published.')
		}
	}

	private boolean mustWaitForInternalScmManagerDeployment() {
		return context.isInternalScmManager()
	}

	private static void ensureRepositoryExists(GitProvider gitProvider,
		String repoTarget,
		String description) {
		gitProvider.createRepository(repoTarget, description, true)
	}
}