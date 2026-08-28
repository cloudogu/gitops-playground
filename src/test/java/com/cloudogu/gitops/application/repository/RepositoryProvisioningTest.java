package com.cloudogu.gitops.application.repository;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.utils.FileSystemUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryProvisioningTest {

	Config config;

	GitRepoFactory gitRepoFactory = mock(GitRepoFactory.class);
	GitHandler gitHandler = mock(GitHandler.class);

	GitProvider tenantProvider = mock(GitProvider.class);
	GitProvider centralProvider = mock(GitProvider.class);

	GitRepo clusterResourcesRepo;
	GitRepo tenantBootstrapRepo;

	@BeforeEach
	void setUp() throws GitAPIException, IOException {
		config = Config.fromMap(Map.of(
			"application", Map.of(
				"namePrefix", "",
				"mirrorRepos", false,
				"openshift", false,
				"insecure", false,
				"gitName", "Cloudogu",
				"gitEmail", "hello@cloudogu.com"
			),
			"scm", Map.of(
				"scmProviderType", ScmProviderType.SCM_MANAGER,
				"scmManager", Map.of("internal", false),
				"gitlab", Map.of("url", "")
			),
			"multiTenant", Map.of(
				"useDedicatedInstance", false,
				"scmManager", Map.of("url", ""),
				"gitlab", Map.of("url", "")
			)
		));

		doReturn(tenantProvider).when(gitHandler).getTenant();
		doReturn(tenantProvider).when(gitHandler).getResourcesScm();

		clusterResourcesRepo = createGitRepoSpy("argocd/cluster-resources", tenantProvider);
		tenantBootstrapRepo = createGitRepoSpy("argocd/cluster-resources", tenantProvider);
	}

	@Test
	void provideWorkspaceCreatesSingleInstanceWorkspaceWithClusterResourcesRepositoryOnly() {
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		RepositoryWorkspace workspace = provisioning.provideWorkspace(createDeploymentContext());

		assertThat(workspace.getClusterResourcesRepository()).isSameAs(clusterResourcesRepo);
		assertThat(workspace.hasTenantBootstrapRepository()).isFalse();

		verify(gitRepoFactory).create(eq("argocd/cluster-resources"), eq(tenantProvider));
		verify(gitHandler).getResourcesScm();
	}

	@Test
	void provideWorkspaceCreatesDedicatedWorkspaceWithCentralClusterResourcesAndTenantBootstrapRepository()
		throws GitAPIException, IOException {

		config.getMultiTenant().setUseDedicatedInstance(true);

		doReturn(centralProvider).when(gitHandler).getResourcesScm();
		doReturn(tenantProvider).when(gitHandler).getTenant();

		clusterResourcesRepo = createGitRepoSpy("argocd/cluster-resources", centralProvider);
		tenantBootstrapRepo = createGitRepoSpy("argocd/cluster-resources", tenantProvider);

		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(centralProvider)))
			.thenReturn(clusterResourcesRepo);
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(tenantBootstrapRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		RepositoryWorkspace workspace = provisioning.provideWorkspace(createDeploymentContext());

		assertThat(workspace.getClusterResourcesRepository()).isSameAs(clusterResourcesRepo);
		assertThat(workspace.getTenantBootstrapRepository()).isSameAs(tenantBootstrapRepo);
		assertThat(workspace.hasTenantBootstrapRepository()).isTrue();

		assertThat(new File(workspace.clusterResourcesRootDir()).getCanonicalPath())
			.isNotEqualTo(new File(workspace.tenantBootstrapRootDir()).getCanonicalPath());

		verify(gitRepoFactory).create(eq("argocd/cluster-resources"), eq(centralProvider));
		verify(gitRepoFactory).create(eq("argocd/cluster-resources"), eq(tenantProvider));
	}

	@Test
	void provideWorkspaceReturnsSameWorkspaceInstanceWhenCalledMultipleTimes() {
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		RepositoryWorkspace firstWorkspace = provisioning.provideWorkspace(createDeploymentContext());
		RepositoryWorkspace secondWorkspace = provisioning.provideWorkspace(createDeploymentContext());

		assertThat(secondWorkspace).isSameAs(firstWorkspace);

		verify(gitRepoFactory, times(1)).create(eq("argocd/cluster-resources"), eq(tenantProvider));
	}

	@Test
	void prepareOnlyPreparesLocalWorkspaceWhenInternalScmManagerMustBeDeployedFirst() throws GitAPIException {
		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		config.getScm().getScmManager().setInternal(true);

		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		provisioning.prepare(createDeploymentContext());

		verify(tenantProvider, never()).createRepository(anyString(), anyString(), anyBoolean());
		verify(clusterResourcesRepo, never()).cloneRepo();
	}

	@Test
	void prepareEnsuresAndClonesRepositoriesWhenScmManagerIsExternal() throws GitAPIException {
		config.getScm().getScmManager().setInternal(false);

		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		provisioning.prepare(createDeploymentContext());

		verify(tenantProvider).createRepository(
			"argocd/cluster-resources",
			"GitOps repo for basic cluster-resources",
			false
		);
		verify(clusterResourcesRepo).cloneRepo();
	}

	@Test
	void ensureRemoteRepositoriesExistCreatesClusterResourcesRepositoryInSingleInstanceMode() {
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		provisioning.provideWorkspace(createDeploymentContext());
		provisioning.ensureRemoteRepositoriesExist();

		verify(tenantProvider).createRepository(
			"argocd/cluster-resources",
			"GitOps repo for basic cluster-resources",
			false
		);
	}

	@Test
	void ensureRemoteRepositoriesExistCreatesBothRepositoriesInDedicatedMode() throws GitAPIException, IOException {
		config.getMultiTenant().setUseDedicatedInstance(true);

		doReturn(centralProvider).when(gitHandler).getResourcesScm();
		doReturn(tenantProvider).when(gitHandler).getTenant();

		clusterResourcesRepo = createGitRepoSpy("argocd/cluster-resources", centralProvider);
		tenantBootstrapRepo = createGitRepoSpy("argocd/cluster-resources", tenantProvider);

		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(centralProvider)))
			.thenReturn(clusterResourcesRepo);
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(tenantBootstrapRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		provisioning.provideWorkspace(createDeploymentContext());
		provisioning.ensureRemoteRepositoriesExist();

		verify(centralProvider).createRepository(
			"argocd/cluster-resources",
			"GitOps repo for basic cluster-resources",
			false
		);

		verify(tenantProvider).createRepository(
			"argocd/cluster-resources",
			"GitOps repo for tenant bootstrap resources",
			false
		);
	}

	@Test
	void ensureRemoteRepositoriesExistIsIdempotent() {
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		provisioning.provideWorkspace(createDeploymentContext());

		provisioning.ensureRemoteRepositoriesExist();
		provisioning.ensureRemoteRepositoriesExist();

		verify(tenantProvider, times(1)).createRepository(
			"argocd/cluster-resources",
			"GitOps repo for basic cluster-resources",
			false
		);
	}

	@Test
	void publishClusterResourcesRepositoryChangesUsesDefaultMessageWhenNoMessageIsProvided() throws GitAPIException {
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(clusterResourcesRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		provisioning.provideWorkspace(createDeploymentContext());

		provisioning.publishClusterResourcesRepositoryChanges("argocd");

		verify(clusterResourcesRepo).commitAndPush("Update argocd resources");
	}

	@Test
	void publishFailsWhenWorkspaceHasNotBeenPrepared() {
		RepositoryProvisioning provisioning = createProvisioning();

		assertThatThrownBy(() -> provisioning.publishClusterResourcesRepositoryChanges("argocd"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Repository workspace must be prepared before repository changes can be published.");
	}

	@Test
	void dedicatedWorkspaceFailsWhenClusterResourcesAndTenantBootstrapUseSameLocalWorkspace() throws IOException {
		config.getMultiTenant().setUseDedicatedInstance(true);

		String sameRootDir = createTempDir("shared-workspace");

		GitRepo sharedClusterRepo = mock(GitRepo.class);
		GitRepo sharedTenantRepo = mock(GitRepo.class);

		sharedClusterRepo.setGitProvider(centralProvider);
		sharedTenantRepo.setGitProvider(tenantProvider);

		doReturn("argocd/cluster-resources").when(sharedClusterRepo).getRepoTarget();
		doReturn("argocd/cluster-resources").when(sharedTenantRepo).getRepoTarget();
		doReturn(sameRootDir).when(sharedClusterRepo).getAbsoluteLocalRepoTmpDir();
		doReturn(sameRootDir).when(sharedTenantRepo).getAbsoluteLocalRepoTmpDir();

		doReturn(centralProvider).when(gitHandler).getResourcesScm();
		doReturn(tenantProvider).when(gitHandler).getTenant();

		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(centralProvider)))
			.thenReturn(sharedClusterRepo);
		when(gitRepoFactory.create(eq("argocd/cluster-resources"), eq(tenantProvider)))
			.thenReturn(sharedTenantRepo);

		RepositoryProvisioning provisioning = createProvisioning();

		assertThatThrownBy(() -> provisioning.provideWorkspace(createDeploymentContext()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Dedicated Multi-Tenant mode requires separate local workspaces")
			.hasMessageContaining(sameRootDir);
	}

	@Test
	void clusterResourcesRepoTargetReturnsUnprefixedTarget() {
		config.getApplication().setNamePrefix("testPrefix-");

		RepositoryProvisioning provisioning = createProvisioning();

		assertThat(provisioning.clusterResourcesRepoTarget()).isEqualTo("argocd/cluster-resources");
	}

	private RepositoryProvisioning createProvisioning() {
		return new RepositoryProvisioning(gitRepoFactory, gitHandler);
	}

	private DeploymentContext createDeploymentContext() {
		return new DeploymentContext(
			Boolean.TRUE.equals(config.getMultiTenant().getUseDedicatedInstance())
				? DeploymentContext.TenantMode.MULTI_TENANT
				: DeploymentContext.TenantMode.SINGLE_TENANT,
			Boolean.TRUE.equals(config.getScm().getScmManager().getInternal())
				? DeploymentContext.ScmManagerDeploymentMode.INTERNAL
				: DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			Boolean.TRUE.equals(config.getApplication().getMirrorRepos()),
			Boolean.TRUE.equals(config.getApplication().getOpenshift())
				? DeploymentContext.ClusterDistribution.OPENSHIFT
				: DeploymentContext.ClusterDistribution.KUBERNETES
		);
	}

	private GitRepo createGitRepoSpy(String repoTarget, GitProvider gitProvider) throws GitAPIException, IOException {
		GitRepo gitRepo = spy(new GitRepo(
			config,
			gitProvider,
			repoTarget,
			new FileSystemUtils()
		));

		doNothing().when(gitRepo).cloneRepo();
		doNothing().when(gitRepo).initLocalRepoIfNeeded();
		doNothing().when(gitRepo).checkoutRemoteMainIfLocalMainMissing();
		doNothing().when(gitRepo).commitAndPush(anyString());

		return gitRepo;
	}

	private static String createTempDir(String prefix) throws IOException {
		return Files.createTempDirectory(prefix).toFile().getCanonicalPath();
	}
}
