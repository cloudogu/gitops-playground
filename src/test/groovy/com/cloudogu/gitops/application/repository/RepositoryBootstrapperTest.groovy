package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.mockito.Mockito.*

class RepositoryBootstrapperTest {

	RepositoryProvisioning repositoryProvisioning = mock(RepositoryProvisioning)

	GitProvider tenantProvider = mock(GitProvider)
	GitProvider centralProvider = mock(GitProvider)

	GitRepo clusterResourcesRepo = mock(GitRepo)
	GitRepo tenantBootstrapRepo = mock(GitRepo)

	@BeforeEach
	void setUp() {
		clusterResourcesRepo.gitProvider = centralProvider
		tenantBootstrapRepo.gitProvider = tenantProvider

		doReturn('argocd/cluster-resources')
			.when(clusterResourcesRepo)
			.getRepoTarget()

		doReturn('argocd/cluster-resources')
			.when(tenantBootstrapRepo)
			.getRepoTarget()

		doReturn(createTempDir('cluster-resources'))
			.when(clusterResourcesRepo)
			.getAbsoluteLocalRepoTmpDir()

		doReturn(createTempDir('tenant-bootstrap'))
			.when(tenantBootstrapRepo)
			.getAbsoluteLocalRepoTmpDir()
	}

	@Test
	void 'bootstrapAfterScmManagerDeployment initializes and pushes cluster resources repository'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo)

		when(repositoryProvisioning.provideWorkspace()).thenReturn(workspace)

		RepositoryBootstrapper bootstrapper = new RepositoryBootstrapper(repositoryProvisioning)

		bootstrapper.bootstrapAfterScmManagerDeployment()

		verify(repositoryProvisioning).ensureRemoteRepositoriesExist()

		verify(clusterResourcesRepo).initLocalRepoIfNeeded()
		verify(clusterResourcesRepo).checkoutMainFromRemoteIfLocalMainMissing()
		verify(clusterResourcesRepo).commitAndPush('Bootstrap cluster-resources repository after SCM-Manager deployment')
	}

	@Test
	void 'bootstrapAfterScmManagerDeployment initializes and pushes both repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepo,
			tenantBootstrapRepo)

		when(repositoryProvisioning.provideWorkspace()).thenReturn(workspace)

		RepositoryBootstrapper bootstrapper = new RepositoryBootstrapper(repositoryProvisioning)

		bootstrapper.bootstrapAfterScmManagerDeployment()

		verify(repositoryProvisioning).ensureRemoteRepositoriesExist()

		verify(clusterResourcesRepo).initLocalRepoIfNeeded()
		verify(clusterResourcesRepo).checkoutMainFromRemoteIfLocalMainMissing()
		verify(clusterResourcesRepo).commitAndPush('Bootstrap cluster-resources repository after SCM-Manager deployment')

		verify(tenantBootstrapRepo).initLocalRepoIfNeeded()
		verify(tenantBootstrapRepo).checkoutMainFromRemoteIfLocalMainMissing()
		verify(tenantBootstrapRepo).commitAndPush('Bootstrap tenant repository after SCM-Manager deployment')
	}

	private static String createTempDir(String prefix) {
		return File.createTempDir(prefix, '').canonicalPath
	}
}