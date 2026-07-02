package com.cloudogu.gitops.application.repository

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

/**
 * Executes the post-SCM-Manager repository bootstrap.*/
@Slf4j
@Singleton
class RepositoryBootstrapper {

	private final RepositoryProvisioning repositoryProvisioning

	RepositoryBootstrapper(RepositoryProvisioning repositoryProvisioning) {
		this.repositoryProvisioning = repositoryProvisioning
	}

	void bootstrapAfterScmManagerDeployment() {
		RepositoryWorkspace workspace = repositoryProvisioning.provideWorkspace()

		repositoryProvisioning.ensureRemoteRepositoriesExist()

		workspace.initLocalRepositoriesIfNeeded()

		/*
		 * After the internal SCM-Manager has created the remote repositories,
		 * the remote main branch may already contain an initial commit, for example
		 * a README.md created by SCM-Manager.
		 *
		 * The locally initialized workspace must start from that remote main branch,
		 * otherwise the first push from GOP may be rejected as non-fast-forward.
		 */
		workspace.alignWithRemoteMainIfPresent()
		workspace.createLocalDirectories()

		workspace.commitAndPushClusterResourcesChanges('Bootstrap cluster-resources repository after SCM-Manager deployment')

		if (workspace.hasTenantBootstrapRepository()) {
			workspace.commitAndPushTenantBootstrapChanges('Bootstrap tenant repository after SCM-Manager deployment')
		}
	}
}