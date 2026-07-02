package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo

import java.nio.file.Path

/**
 * Represents the prepared local GitOps repository workspace used during a GOP deployment.
 *
 * <p>The workspace provides access to the local checkout of the {@code cluster-resources}
 * repository. This repository contains the generated GitOps resources that are consumed by
 * ArgoCD, for example applications and projects.</p>
 *
 * <p>In single-instance setups only the {@code cluster-resources} repository is required.
 * In dedicated multi-tenant setups an additional tenant bootstrap repository is required.
 * This second repository contains the bootstrap resources for the tenant ArgoCD instance,
 * while the regular {@code cluster-resources} repository is used by the central ArgoCD
 * instance to bootstrap/manage tenant resources.</p>
 *
 * <p>This class does not decide which repositories are needed. That decision belongs to
 * {@link RepositoryProvisioning}. This class only exposes the prepared repositories and
 * the directory structure that tools can write to.</p>*/
class RepositoryWorkspace {

	final GitRepo clusterResourcesRepository
	final GitRepo tenantBootstrapRepository

	RepositoryWorkspace(GitRepo clusterResourcesRepository,
		GitRepo tenantBootstrapRepository = null) {
		this.clusterResourcesRepository = clusterResourcesRepository
		this.tenantBootstrapRepository = tenantBootstrapRepository
	}

	boolean hasTenantBootstrapRepository() {
		return tenantBootstrapRepository != null
	}

	/**
	 * Returns the tenant bootstrap repository or fails if this workspace was created for
	 * a single-instance setup.	*/
	GitRepo tenantBootstrapRepositoryOrFail() {
		if (tenantBootstrapRepository == null) {
			throw new IllegalStateException('Tenant bootstrap repository is not available in single-instance mode.')
		}

		return tenantBootstrapRepository
	}

	void createLocalDirectories() {
		Path.of(clusterResourcesRootDir()).toFile().mkdirs()
		Path.of(clusterResourcesAppsDir()).toFile().mkdirs()
		Path.of(clusterResourcesArgoCdDir()).toFile().mkdirs()
		Path.of(clusterResourcesApplicationsDir()).toFile().mkdirs()
		Path.of(clusterResourcesProjectsDir()).toFile().mkdirs()

		if (hasTenantBootstrapRepository()) {
			Path.of(tenantBootstrapRootDir()).toFile().mkdirs()
			Path.of(tenantBootstrapAppsDir()).toFile().mkdirs()
			Path.of(tenantBootstrapArgoCdDir()).toFile().mkdirs()
			Path.of(tenantBootstrapApplicationsDir()).toFile().mkdirs()
			Path.of(tenantBootstrapProjectsDir()).toFile().mkdirs()
		}
	}

	void cloneRepositories() {
		clusterResourcesRepository.cloneRepo()

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().cloneRepo()
		}
	}

	/**
	 * Initializes local repositories when they cannot be cloned yet.
	 *
	 * <p>This is needed when GOP deploys an internal SCM-Manager first. In that case,
	 * the remote repositories are not available at the beginning of the deployment,
	 * but tools still need local directories to write their generated resources.</p>	*/
	void initLocalRepositoriesIfNeeded() {
		clusterResourcesRepository.initLocalRepoIfNeeded()

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().initLocalRepoIfNeeded()
		}
	}

	String clusterResourcesRootDir() {
		return clusterResourcesRepository.getAbsoluteLocalRepoTmpDir()
	}

	String clusterResourcesAppsDir() {
		return Path.of(clusterResourcesRootDir(), 'apps').toString()
	}

	String clusterResourcesArgoCdDir() {
		return Path.of(clusterResourcesAppsDir(), 'argocd').toString()
	}

	String clusterResourcesApplicationsDir() {
		return Path.of(clusterResourcesArgoCdDir(), 'applications').toString()
	}

	String clusterResourcesProjectsDir() {
		return Path.of(clusterResourcesArgoCdDir(), 'projects').toString()
	}

	String tenantBootstrapRootDir() {
		return tenantBootstrapRepositoryOrFail().getAbsoluteLocalRepoTmpDir()
	}

	String tenantBootstrapAppsDir() {
		return Path.of(tenantBootstrapRootDir(), 'apps').toString()
	}

	String tenantBootstrapArgoCdDir() {
		return Path.of(tenantBootstrapAppsDir(), 'argocd').toString()
	}

	String tenantBootstrapApplicationsDir() {
		return Path.of(tenantBootstrapArgoCdDir(), 'applications').toString()
	}

	String tenantBootstrapProjectsDir() {
		return Path.of(tenantBootstrapArgoCdDir(), 'projects').toString()
	}

	void commitAndPushClusterResourcesAndTenantBootstrapChanges(String message) {
		commitAndPushClusterResourcesChanges(message)

		if (hasTenantBootstrapRepository()) {
			commitAndPushTenantBootstrapChanges(message)
		}
	}

	void commitAndPushTenantBootstrapChanges(String message) {
		tenantBootstrapRepositoryOrFail().commitAndPush(message)
	}

	void commitAndPushClusterResourcesChanges(String message) {
		clusterResourcesRepository.commitAndPush(message)
	}

	/**
	 * Aligns locally initialized repositories with the remote main branch if it already exists.	*/
	void checkoutMainFromRemoteIfLocalMainMissing() {
		clusterResourcesRepository.checkoutMainFromRemoteIfLocalMainMissing()

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().checkoutMainFromRemoteIfLocalMainMissing()
		}
	}

}