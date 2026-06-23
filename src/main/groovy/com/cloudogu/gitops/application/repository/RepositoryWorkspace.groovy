package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo

import java.nio.file.Path

class RepositoryWorkspace {

	final GitRepo clusterResourcesRepository
	final GitRepo tenantBootstrapRepository

	RepositoryWorkspace(
		GitRepo clusterResourcesRepository,
		GitRepo tenantBootstrapRepository = null
	) {
		this.clusterResourcesRepository = clusterResourcesRepository
		this.tenantBootstrapRepository = tenantBootstrapRepository
	}

	boolean hasTenantBootstrapRepository() {
		tenantBootstrapRepository != null
	}

	GitRepo tenantBootstrapRepositoryOrFail() {
		if (tenantBootstrapRepository == null) {
			throw new IllegalStateException(
				'Tenant bootstrap repository is not available in single-instance mode.'
			)
		}

		return tenantBootstrapRepository
	}

	void prepareLocalDirectories() {
		Path.of(clusterResourcesRootDir()).toFile().mkdirs()
		Path.of(clusterResourcesAppsDir()).toFile().mkdirs()

		if (hasTenantBootstrapRepository()) {
			Path.of(tenantBootstrapRootDir()).toFile().mkdirs()
			Path.of(tenantBootstrapAppsDir()).toFile().mkdirs()
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
		clusterResourcesRepository.getAbsoluteLocalRepoTmpDir()
	}

	String clusterResourcesAppsDir() {
		Path.of(clusterResourcesRootDir(), 'apps').toString()
	}

	String clusterResourcesAppDir(String toolName) {
		Path.of(clusterResourcesAppsDir(), toolName).toString()
	}

	String tenantBootstrapRootDir() {
		tenantBootstrapRepositoryOrFail().getAbsoluteLocalRepoTmpDir()
	}

	String tenantBootstrapAppsDir() {
		Path.of(tenantBootstrapRootDir(), 'apps').toString()
	}

	String tenantBootstrapAppDir(String toolName) {
		Path.of(tenantBootstrapAppsDir(), toolName).toString()
	}

	String clusterResourcesRepositoryUrl() {
		"${clusterResourcesRepository.gitProvider.repoPrefix()}${clusterResourcesRepository.repoTarget}.git"
	}

	void writeClusterResourcesFile(String relativePath, String content) {
		clusterResourcesRepository.writeFile(relativePath, content)
	}

	void writeTenantBootstrapFile(String relativePath, String content) {
		tenantBootstrapRepositoryOrFail().writeFile(relativePath, content)
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
}