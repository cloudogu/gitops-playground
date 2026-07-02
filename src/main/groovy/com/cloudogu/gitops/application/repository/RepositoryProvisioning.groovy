package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

/**
 * Prepares and makes the required GitOps repositories available during a GOP deployment.
 *
 * <p>This class is responsible for creating the shared {@link RepositoryWorkspace},
 * ensuring that the required remote repositories exist, and cloning those repositories
 * when they are already available.</p>
 *
 * <p>The main repository managed here is the {@code cluster-resources} repository.
 * It contains the generated GitOps resources that are consumed by ArgoCD, for example
 * applications and projects.</p>
 *
 * <p>In dedicated multi-tenant setups, two repository workspaces are required:</p>
 * <ul>
 *   <li>the cluster-resources repository in the central SCM-Manager, used by the central ArgoCD instance</li>
 *   <li>the tenant bootstrap repository in the tenant SCM-Manager, used to bootstrap the tenant ArgoCD instance</li>
 * </ul>
 *
 * <p>Both repositories can have the same logical repository target, but they must use
 * separate local workspaces because their templates may contain overlapping paths.</p>
 *
 * <p>This class does not generate tool-specific resources. Tools write their files into
 * the prepared {@link RepositoryWorkspace}. Repository provisioning only coordinates
 * repository availability, local workspace preparation, and commit/push entry points.</p>*/
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

		/**
		 * Returns the shared repository workspace for the current deployment.
		 *
		 * <p>The workspace is created lazily and reused afterwards so all tools write to the same
		 * local repository checkout.</p>		*/
		provideWorkspace()

		if (mustWaitForInternalScmManagerDeployment()) {
			log.info('Preparing local repository workspace only because internal SCM-Manager is not deployed yet.')
			workspace.createLocalDirectories()
			return
		}

		/**
		 * Ensures that all remote repositories required by the current workspace exist.
		 *
		 * <p>In single-instance setups this only affects the cluster-resources repository.
		 * In dedicated multi-tenant setups this also ensures the tenant bootstrap repository.</p>		*/
		ensureRemoteRepositoriesExist()

		/**
		 * Clones all repositories that belong to the prepared workspace.
		 *
		 * <p>This is only done once per deployment run to keep all tools working on the same
		 * local checkout.</p>		*/
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

	/**
	 * Commits and pushes changes in the cluster-resources repository.
	 *
	 * <p>This is used after tools have written generated resources into the shared workspace.</p>	*/
	void publishClusterResourcesRepositoryChanges(String toolName,
		String message = null) {
		assertWorkspacePrepared()

		workspace.commitAndPushClusterResourcesChanges((message ?: "Update ${toolName} resources").toString())
	}

	/**
	 * Commits and pushes changes in both the cluster-resources repository and, if available,
	 * the tenant bootstrap repository.
	 *
	 * <p>This is mainly relevant for dedicated multi-tenant setups where resources may be
	 * written to both repository workspaces.</p>	*/
	void publishClusterResourcesAndTenantBootstrapRepositoryChanges(String toolName,
		String message = null) {
		assertWorkspacePrepared()

		workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges((message ?: "Update ${toolName} resources").toString())
	}

	String clusterResourcesRepoTarget() {
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
		 * In dedicated multi-tenant mode, the cluster-resources repository used by the central
		 * ArgoCD and the tenant bootstrap repository belong to different SCM contexts.
		 * Therefore both repositories are represented explicitly, even though they use the same
		 * logical repository target.
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