package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo

import java.nio.file.Path

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

	GitRepo tenantBootstrapRepositoryOrFail() {
		if (tenantBootstrapRepository == null) {
			throw new IllegalStateException('Tenant bootstrap repository is not available in single-instance mode.')
		}

		return tenantBootstrapRepository
	}

	void prepareLocalDirectories() {
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

	void commitAndPushClusterResourcesChanges(String message) {
		clusterResourcesRepository.commitAndPush(message)
	}

	void commitAndPushTenantBootstrapChanges(String message) {
		tenantBootstrapRepositoryOrFail().commitAndPush(message)
	}

	void commitAndPushClusterResourcesAndTenantBootstrapChanges(String message) {
		commitAndPushClusterResourcesChanges(message)

		if (hasTenantBootstrapRepository()) {
			commitAndPushTenantBootstrapChanges(message)
		}
	}

	void checkoutMainFromRemoteIfLocalMainMissing() {
		clusterResourcesRepository.checkoutMainFromRemoteIfLocalMainMissing()

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().checkoutMainFromRemoteIfLocalMainMissing()
		}
	}

}