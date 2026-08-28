package com.cloudogu.gitops.application.repository;

import com.cloudogu.gitops.infrastructure.git.GitRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RepositoryWorkspaceTest {

	GitRepo clusterResourcesRepository = mock(GitRepo.class);
	GitRepo tenantBootstrapRepository = mock(GitRepo.class);

	String clusterResourcesRootDir;
	String tenantBootstrapRootDir;

	@BeforeEach
	void setUp() throws IOException {
		clusterResourcesRootDir = createTempDir("cluster-resources");
		tenantBootstrapRootDir = createTempDir("tenant-bootstrap");

		doReturn(clusterResourcesRootDir)
			.when(clusterResourcesRepository)
			.getAbsoluteLocalRepoTmpDir();

		doReturn(tenantBootstrapRootDir)
			.when(tenantBootstrapRepository)
			.getAbsoluteLocalRepoTmpDir();
	}

	@Test
	void hasTenantBootstrapRepositoryReturnsFalseInSingleInstanceMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		assertThat(workspace.hasTenantBootstrapRepository()).isFalse();
	}

	@Test
	void hasTenantBootstrapRepositoryReturnsTrueInDedicatedMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		assertThat(workspace.hasTenantBootstrapRepository()).isTrue();
	}

	@Test
	void tenantBootstrapRepositoryOrFailReturnsTenantBootstrapRepositoryWhenAvailable() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		assertThat(workspace.tenantBootstrapRepositoryOrFail()).isSameAs(tenantBootstrapRepository);
	}

	@Test
	void tenantBootstrapRepositoryOrFailThrowsInSingleInstanceMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		assertThatThrownBy(workspace::tenantBootstrapRepositoryOrFail)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Tenant bootstrap repository is not available in single-instance mode.");
	}

	@Test
	void createLocalDirectoriesCreatesClusterResourcesDirectoryStructureInSingleInstanceMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		workspace.createLocalDirectories();

		assertThat(Path.of(clusterResourcesRootDir)).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps")).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps", "argocd")).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps", "argocd", "applications")).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps", "argocd", "projects")).exists();

		assertThat(Path.of(tenantBootstrapRootDir, "apps")).doesNotExist();
	}

	@Test
	void createLocalDirectoriesCreatesClusterResourcesAndTenantBootstrapDirectoryStructuresInDedicatedMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.createLocalDirectories();

		assertThat(Path.of(clusterResourcesRootDir)).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps")).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps", "argocd")).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps", "argocd", "applications")).exists();
		assertThat(Path.of(clusterResourcesRootDir, "apps", "argocd", "projects")).exists();

		assertThat(Path.of(tenantBootstrapRootDir)).exists();
		assertThat(Path.of(tenantBootstrapRootDir, "apps")).exists();
		assertThat(Path.of(tenantBootstrapRootDir, "apps", "argocd")).exists();
		assertThat(Path.of(tenantBootstrapRootDir, "apps", "argocd", "applications")).exists();
		assertThat(Path.of(tenantBootstrapRootDir, "apps", "argocd", "projects")).exists();
	}

	@Test
	void cloneRepositoriesClonesOnlyClusterResourcesRepositoryInSingleInstanceMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		workspace.cloneRepositories();

		verify(clusterResourcesRepository).cloneRepo();
		verifyNoInteractions(tenantBootstrapRepository);
	}

	@Test
	void cloneRepositoriesClonesClusterResourcesAndTenantBootstrapRepositoriesInDedicatedMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.cloneRepositories();

		verify(clusterResourcesRepository).cloneRepo();
		verify(tenantBootstrapRepository).cloneRepo();
	}

	@Test
	void initLocalRepositoriesIfNeededInitializesOnlyClusterResourcesRepositoryInSingleInstanceMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		workspace.initLocalRepositoriesIfNeeded();

		verify(clusterResourcesRepository).initLocalRepoIfNeeded();
		verifyNoInteractions(tenantBootstrapRepository);
	}

	@Test
	void initLocalRepositoriesIfNeededInitializesClusterResourcesAndTenantBootstrapRepositoriesInDedicatedMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.initLocalRepositoriesIfNeeded();

		verify(clusterResourcesRepository).initLocalRepoIfNeeded();
		verify(tenantBootstrapRepository).initLocalRepoIfNeeded();
	}

	@Test
	void clusterResourcesPathMethodsReturnExpectedPaths() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		assertThat(workspace.clusterResourcesRootDir()).isEqualTo(clusterResourcesRootDir);
		assertThat(workspace.clusterResourcesAppsDir()).isEqualTo(Path.of(clusterResourcesRootDir, "apps").toString());
		assertThat(workspace.clusterResourcesArgoCdDir()).isEqualTo(Path.of(
			clusterResourcesRootDir,
			"apps",
			"argocd"
		).toString());
		assertThat(workspace.clusterResourcesApplicationsDir()).isEqualTo(Path.of(
			clusterResourcesRootDir,
			"apps",
			"argocd",
			"applications"
		).toString());
		assertThat(workspace.clusterResourcesProjectsDir()).isEqualTo(Path.of(
			clusterResourcesRootDir,
			"apps",
			"argocd",
			"projects"
		).toString());
	}

	@Test
	void tenantBootstrapPathMethodsReturnExpectedPathsInDedicatedMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		assertThat(workspace.tenantBootstrapRootDir()).isEqualTo(tenantBootstrapRootDir);
		assertThat(workspace.tenantBootstrapAppsDir()).isEqualTo(Path.of(tenantBootstrapRootDir, "apps").toString());
		assertThat(workspace.tenantBootstrapArgoCdDir()).isEqualTo(Path.of(
			tenantBootstrapRootDir,
			"apps",
			"argocd"
		).toString());
		assertThat(workspace.tenantBootstrapApplicationsDir()).isEqualTo(Path.of(
			tenantBootstrapRootDir,
			"apps",
			"argocd",
			"applications"
		).toString());
		assertThat(workspace.tenantBootstrapProjectsDir()).isEqualTo(Path.of(
			tenantBootstrapRootDir,
			"apps",
			"argocd",
			"projects"
		).toString());
	}

	@Test
	void tenantBootstrapPathMethodsThrowInSingleInstanceMode() {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		assertThatThrownBy(workspace::tenantBootstrapRootDir)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Tenant bootstrap repository is not available in single-instance mode.");

		assertThatThrownBy(workspace::tenantBootstrapAppsDir)
			.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(workspace::tenantBootstrapArgoCdDir)
			.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(workspace::tenantBootstrapApplicationsDir)
			.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(workspace::tenantBootstrapProjectsDir)
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void commitAndPushClusterResourcesChangesCommitsOnlyClusterResourcesRepository() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.commitAndPushClusterResourcesChanges("Update cluster resources");

		verify(clusterResourcesRepository).commitAndPush("Update cluster resources");
		verify(tenantBootstrapRepository, never()).commitAndPush(anyString());
	}

	@Test
	void commitAndPushTenantBootstrapChangesCommitsTenantBootstrapRepositoryWhenAvailable() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.commitAndPushTenantBootstrapChanges("Update tenant bootstrap");

		verify(tenantBootstrapRepository).commitAndPush("Update tenant bootstrap");
		verify(clusterResourcesRepository, never()).commitAndPush(anyString());
	}

	@Test
	void commitAndPushTenantBootstrapChangesThrowsInSingleInstanceMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		assertThatThrownBy(() -> workspace.commitAndPushTenantBootstrapChanges("Update tenant bootstrap"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Tenant bootstrap repository is not available in single-instance mode.");

		verify(clusterResourcesRepository, never()).commitAndPush(anyString());
	}

	@Test
	void commitAndPushClusterResourcesAndTenantBootstrapChangesCommitsOnlyClusterResourcesRepositoryInSingleInstanceMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges("Update resources");

		verify(clusterResourcesRepository).commitAndPush("Update resources");
		verifyNoInteractions(tenantBootstrapRepository);
	}

	@Test
	void commitAndPushClusterResourcesAndTenantBootstrapChangesCommitsBothRepositoriesInDedicatedMode() throws GitAPIException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges("Update resources");

		verify(clusterResourcesRepository).commitAndPush("Update resources");
		verify(tenantBootstrapRepository).commitAndPush("Update resources");
	}

	@Test
	void alignWithRemoteMainIfPresentChecksOutOnlyClusterResourcesRepositoryInSingleInstanceMode() throws GitAPIException, IOException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(clusterResourcesRepository);

		workspace.alignWithRemoteMainIfPresent();

		verify(clusterResourcesRepository).checkoutRemoteMainIfLocalMainMissing();
		verifyNoInteractions(tenantBootstrapRepository);
	}

	@Test
	void alignWithRemoteMainIfPresentChecksOutBothRepositoriesInDedicatedMode() throws GitAPIException, IOException {
		RepositoryWorkspace workspace = new RepositoryWorkspace(
			clusterResourcesRepository,
			tenantBootstrapRepository
		);

		workspace.alignWithRemoteMainIfPresent();

		verify(clusterResourcesRepository).checkoutRemoteMainIfLocalMainMissing();
		verify(tenantBootstrapRepository).checkoutRemoteMainIfLocalMainMissing();
	}

	private static String createTempDir(String prefix) throws IOException {
		return Files.createTempDirectory(prefix).toFile().getCanonicalPath();
	}
}
