package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy
import static org.mockito.Mockito.*

class RepositoryWorkspaceTest {

	GitRepo clusterResourcesRepository = mock(GitRepo)
	GitRepo tenantBootstrapRepository = mock(GitRepo)

	String clusterResourcesRootDir
	String tenantBootstrapRootDir

	@BeforeEach
	void setUp() {
		clusterResourcesRootDir = createTempDir('cluster-resources')
		tenantBootstrapRootDir = createTempDir('tenant-bootstrap')

		doReturn(clusterResourcesRootDir)
			.when(clusterResourcesRepository)
			.getAbsoluteLocalRepoTmpDir()

		doReturn(tenantBootstrapRootDir)
			.when(tenantBootstrapRepository)
			.getAbsoluteLocalRepoTmpDir()
	}

	@Test
	void 'hasTenantBootstrapRepository returns false in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		assertThat(workspace.hasTenantBootstrapRepository()).isFalse()
	}

	@Test
	void 'hasTenantBootstrapRepository returns true in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		assertThat(workspace.hasTenantBootstrapRepository()).isTrue()
	}

	@Test
	void 'tenantBootstrapRepositoryOrFail returns tenant bootstrap repository when available'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		assertThat(workspace.tenantBootstrapRepositoryOrFail()).isSameAs(tenantBootstrapRepository)
	}

	@Test
	void 'tenantBootstrapRepositoryOrFail throws in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		assertThatThrownBy {
			workspace.tenantBootstrapRepositoryOrFail()
		}.isInstanceOf(IllegalStateException)
			.hasMessage('Tenant bootstrap repository is not available in single-instance mode.')
	}

	@Test
	void 'createLocalDirectories creates cluster resources directory structure in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		workspace.createLocalDirectories()

		assertThat(Path.of(clusterResourcesRootDir)).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps')).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps', 'argocd')).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps', 'argocd', 'applications')).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps', 'argocd', 'projects')).exists()

		assertThat(Path.of(tenantBootstrapRootDir, 'apps')).doesNotExist()
	}

	@Test
	void 'createLocalDirectories creates cluster resources and tenant bootstrap directory structures in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.createLocalDirectories()

		assertThat(Path.of(clusterResourcesRootDir)).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps')).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps', 'argocd')).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps', 'argocd', 'applications')).exists()
		assertThat(Path.of(clusterResourcesRootDir, 'apps', 'argocd', 'projects')).exists()

		assertThat(Path.of(tenantBootstrapRootDir)).exists()
		assertThat(Path.of(tenantBootstrapRootDir, 'apps')).exists()
		assertThat(Path.of(tenantBootstrapRootDir, 'apps', 'argocd')).exists()
		assertThat(Path.of(tenantBootstrapRootDir, 'apps', 'argocd', 'applications')).exists()
		assertThat(Path.of(tenantBootstrapRootDir, 'apps', 'argocd', 'projects')).exists()
	}

	@Test
	void 'cloneRepositories clones only cluster resources repository in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		workspace.cloneRepositories()

		verify(clusterResourcesRepository).cloneRepo()
		verifyNoInteractions(tenantBootstrapRepository)
	}

	@Test
	void 'cloneRepositories clones cluster resources and tenant bootstrap repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.cloneRepositories()

		verify(clusterResourcesRepository).cloneRepo()
		verify(tenantBootstrapRepository).cloneRepo()
	}

	@Test
	void 'initLocalRepositoriesIfNeeded initializes only cluster resources repository in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		workspace.initLocalRepositoriesIfNeeded()

		verify(clusterResourcesRepository).initLocalRepoIfNeeded()
		verifyNoInteractions(tenantBootstrapRepository)
	}

	@Test
	void 'initLocalRepositoriesIfNeeded initializes cluster resources and tenant bootstrap repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.initLocalRepositoriesIfNeeded()

		verify(clusterResourcesRepository).initLocalRepoIfNeeded()
		verify(tenantBootstrapRepository).initLocalRepoIfNeeded()
	}

	@Test
	void 'cluster resources path methods return expected paths'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		assertThat(workspace.clusterResourcesRootDir()).isEqualTo(clusterResourcesRootDir)
		assertThat(workspace.clusterResourcesAppsDir()).isEqualTo(Path.of(clusterResourcesRootDir, 'apps').toString())
		assertThat(workspace.clusterResourcesArgoCdDir()).isEqualTo(Path.of(clusterResourcesRootDir, 'apps', 'argocd').toString())
		assertThat(workspace.clusterResourcesApplicationsDir()).isEqualTo(Path.of(clusterResourcesRootDir, 'apps', 'argocd', 'applications').toString())
		assertThat(workspace.clusterResourcesProjectsDir()).isEqualTo(Path.of(clusterResourcesRootDir, 'apps', 'argocd', 'projects').toString())
	}

	@Test
	void 'tenant bootstrap path methods return expected paths in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		assertThat(workspace.tenantBootstrapRootDir()).isEqualTo(tenantBootstrapRootDir)
		assertThat(workspace.tenantBootstrapAppsDir()).isEqualTo(Path.of(tenantBootstrapRootDir, 'apps').toString())
		assertThat(workspace.tenantBootstrapArgoCdDir()).isEqualTo(Path.of(tenantBootstrapRootDir, 'apps', 'argocd').toString())
		assertThat(workspace.tenantBootstrapApplicationsDir()).isEqualTo(Path.of(tenantBootstrapRootDir, 'apps', 'argocd', 'applications').toString())
		assertThat(workspace.tenantBootstrapProjectsDir()).isEqualTo(Path.of(tenantBootstrapRootDir, 'apps', 'argocd', 'projects').toString())
	}

	@Test
	void 'tenant bootstrap path methods throw in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		assertThatThrownBy {
			workspace.tenantBootstrapRootDir()
		}.isInstanceOf(IllegalStateException)
			.hasMessage('Tenant bootstrap repository is not available in single-instance mode.')

		assertThatThrownBy {
			workspace.tenantBootstrapAppsDir()
		}.isInstanceOf(IllegalStateException)

		assertThatThrownBy {
			workspace.tenantBootstrapArgoCdDir()
		}.isInstanceOf(IllegalStateException)

		assertThatThrownBy {
			workspace.tenantBootstrapApplicationsDir()
		}.isInstanceOf(IllegalStateException)

		assertThatThrownBy {
			workspace.tenantBootstrapProjectsDir()
		}.isInstanceOf(IllegalStateException)
	}

	@Test
	void 'commitAndPushClusterResourcesChanges commits only cluster resources repository'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.commitAndPushClusterResourcesChanges('Update cluster resources')

		verify(clusterResourcesRepository).commitAndPush('Update cluster resources')
		verify(tenantBootstrapRepository, never()).commitAndPush(any(String))
	}

	@Test
	void 'commitAndPushTenantBootstrapChanges commits tenant bootstrap repository when available'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.commitAndPushTenantBootstrapChanges('Update tenant bootstrap')

		verify(tenantBootstrapRepository).commitAndPush('Update tenant bootstrap')
		verify(clusterResourcesRepository, never()).commitAndPush(any(String))
	}

	@Test
	void 'commitAndPushTenantBootstrapChanges throws in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		assertThatThrownBy {
			workspace.commitAndPushTenantBootstrapChanges('Update tenant bootstrap')
		}.isInstanceOf(IllegalStateException)
			.hasMessage('Tenant bootstrap repository is not available in single-instance mode.')

		verify(clusterResourcesRepository, never()).commitAndPush(any(String))
	}

	@Test
	void 'commitAndPushClusterResourcesAndTenantBootstrapChanges commits only cluster resources repository in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges('Update resources')

		verify(clusterResourcesRepository).commitAndPush('Update resources')
		verifyNoInteractions(tenantBootstrapRepository)
	}

	@Test
	void 'commitAndPushClusterResourcesAndTenantBootstrapChanges commits both repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges('Update resources')

		verify(clusterResourcesRepository).commitAndPush('Update resources')
		verify(tenantBootstrapRepository).commitAndPush('Update resources')
	}

	@Test
	void 'alignWithRemoteMainIfPresent checks out only cluster resources repository in single-instance mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository)

		workspace.alignWithRemoteMainIfPresent()

		verify(clusterResourcesRepository).checkoutRemoteMainIfLocalMainMissing()
		verifyNoInteractions(tenantBootstrapRepository)
	}

	@Test
	void 'alignWithRemoteMainIfPresent checks out both repositories in dedicated mode'() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository,
			tenantBootstrapRepository)

		workspace.alignWithRemoteMainIfPresent()

		verify(clusterResourcesRepository).checkoutRemoteMainIfLocalMainMissing()
		verify(tenantBootstrapRepository).checkoutRemoteMainIfLocalMainMissing()
	}

	private static String createTempDir(String prefix) {
		return File.createTempDir(prefix, '').canonicalPath
	}
}