package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

class RepositoryProvisioningTest {

	Config config

	GitRepoFactory gitRepoFactory = mock(GitRepoFactory)
	GitHandler gitHandler = mock(GitHandler)

	GitProvider tenantProvider = mock(GitProvider)
	GitProvider centralProvider = mock(GitProvider)

	GitRepo clusterResourcesRepo
	GitRepo tenantBootstrapRepo

	@BeforeEach
	void setUp() {
		config = Config.fromMap(application: [namePrefix : '',
		                                      mirrorRepos: false,
		                                      openshift  : false,
		                                      insecure   : false,
		                                      gitName    : 'Cloudogu',
		                                      gitEmail   : 'hello@cloudogu.com'],
			scm: [scmProviderType: ScmProviderType.SCM_MANAGER,
			      scmManager     : [internal: false],
			      gitlab         : [url: '']],
			multiTenant: [useDedicatedInstance: false,
			              scmManager          : [url: ''],
			              gitlab              : [url: '']])

		doReturn(tenantProvider).when(gitHandler).getTenant()
		doReturn(tenantProvider).when(gitHandler).getResourcesScm()

		clusterResourcesRepo = createGitRepoSpy('argocd/cluster-resources', tenantProvider)
		tenantBootstrapRepo = createGitRepoSpy('argocd/cluster-resources', tenantProvider)
	}

	@Test
	void 'provideWorkspace creates single-instance workspace with cluster-resources repository only'() {
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		RepositoryWorkspace workspace = provisioning.provideWorkspace()

		assertThat(workspace.clusterResourcesRepository).isSameAs(clusterResourcesRepo)
		assertThat(workspace.hasTenantBootstrapRepository()).isFalse()

		verify(gitRepoFactory).create('argocd/cluster-resources', tenantProvider)
		verify(gitHandler).getResourcesScm()
	}

	@Test
	void 'provideWorkspace creates dedicated workspace with central cluster-resources and tenant bootstrap repository'() {
		config.multiTenant.useDedicatedInstance = true

		doReturn(centralProvider).when(gitHandler).getResourcesScm()
		doReturn(tenantProvider).when(gitHandler).getTenant()

		clusterResourcesRepo = createGitRepoSpy('argocd/cluster-resources', centralProvider)
		tenantBootstrapRepo = createGitRepoSpy('argocd/cluster-resources', tenantProvider)

		when(gitRepoFactory.create('argocd/cluster-resources', centralProvider))
			.thenReturn(clusterResourcesRepo)
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(tenantBootstrapRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		RepositoryWorkspace workspace = provisioning.provideWorkspace()

		assertThat(workspace.clusterResourcesRepository).isSameAs(clusterResourcesRepo)
		assertThat(workspace.tenantBootstrapRepository).isSameAs(tenantBootstrapRepo)
		assertThat(workspace.hasTenantBootstrapRepository()).isTrue()

		assertThat(new File(workspace.clusterResourcesRootDir()).canonicalPath)
			.isNotEqualTo(new File(workspace.tenantBootstrapRootDir()).canonicalPath)

		verify(gitRepoFactory).create('argocd/cluster-resources', centralProvider)
		verify(gitRepoFactory).create('argocd/cluster-resources', tenantProvider)
	}

	@Test
	void 'provideWorkspace returns same workspace instance when called multiple times'() {
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		RepositoryWorkspace firstWorkspace = provisioning.provideWorkspace()
		RepositoryWorkspace secondWorkspace = provisioning.provideWorkspace()

		assertThat(secondWorkspace).isSameAs(firstWorkspace)

		verify(gitRepoFactory, times(1)).create('argocd/cluster-resources', tenantProvider)
	}

	@Test
	void 'prepare only prepares local workspace when internal SCM-Manager must be deployed first'() {
		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager.internal = true

		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		provisioning.prepare()

		verify(tenantProvider, never()).createRepository(any(String), any(String), any(Boolean))
		verify(clusterResourcesRepo, never()).cloneRepo()
	}

	@Test
	void 'prepare ensures and clones repositories when SCM-Manager is external'() {
		config.scm.scmManager.internal = false

		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		provisioning.prepare()

		verify(tenantProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for basic cluster-resources',
			true)
		verify(clusterResourcesRepo).cloneRepo()
	}

	@Test
	void 'ensureRemoteRepositoriesExist creates cluster-resources repository in single-instance mode'() {
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		provisioning.provideWorkspace()
		provisioning.ensureRemoteRepositoriesExist()

		verify(tenantProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for basic cluster-resources',
			true)
	}

	@Test
	void 'ensureRemoteRepositoriesExist creates both repositories in dedicated mode'() {
		config.multiTenant.useDedicatedInstance = true

		doReturn(centralProvider).when(gitHandler).getResourcesScm()
		doReturn(tenantProvider).when(gitHandler).getTenant()

		clusterResourcesRepo = createGitRepoSpy('argocd/cluster-resources', centralProvider)
		tenantBootstrapRepo = createGitRepoSpy('argocd/cluster-resources', tenantProvider)

		when(gitRepoFactory.create('argocd/cluster-resources', centralProvider))
			.thenReturn(clusterResourcesRepo)
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(tenantBootstrapRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		provisioning.provideWorkspace()
		provisioning.ensureRemoteRepositoriesExist()

		verify(centralProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for basic cluster-resources',
			true)

		verify(tenantProvider).createRepository('argocd/cluster-resources',
			'GitOps repo for tenant bootstrap resources',
			true)
	}

	@Test
	void 'ensureRemoteRepositoriesExist is idempotent'() {
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		provisioning.provideWorkspace()

		provisioning.ensureRemoteRepositoriesExist()
		provisioning.ensureRemoteRepositoriesExist()

		verify(tenantProvider, times(1)).createRepository('argocd/cluster-resources',
			'GitOps repo for basic cluster-resources',
			true)
	}

	@Test
	void 'publishClusterResourcesRepositoryChanges uses default message when no message is provided'() {
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(clusterResourcesRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		provisioning.provideWorkspace()

		provisioning.publishClusterResourcesRepositoryChanges('argocd')

		verify(clusterResourcesRepo).commitAndPush('Update argocd resources')
	}

	@Test
	void 'publish fails when workspace has not been prepared'() {
		RepositoryProvisioning provisioning = createProvisioning()

		assertThatThrownBy {
			provisioning.publishClusterResourcesRepositoryChanges('argocd')
		}.isInstanceOf(IllegalStateException)
			.hasMessage('Repository workspace must be prepared before repository changes can be published.')
	}

	@Test
	void 'dedicated workspace fails when cluster resources and tenant bootstrap use same local workspace'() {
		config.multiTenant.useDedicatedInstance = true

		String sameRootDir = createTempDir('shared-workspace')

		GitRepo sharedClusterRepo = mock(GitRepo)
		GitRepo sharedTenantRepo = mock(GitRepo)

		sharedClusterRepo.gitProvider = centralProvider
		sharedTenantRepo.gitProvider = tenantProvider

		doReturn('argocd/cluster-resources').when(sharedClusterRepo).getRepoTarget()
		doReturn('argocd/cluster-resources').when(sharedTenantRepo).getRepoTarget()
		doReturn(sameRootDir).when(sharedClusterRepo).getAbsoluteLocalRepoTmpDir()
		doReturn(sameRootDir).when(sharedTenantRepo).getAbsoluteLocalRepoTmpDir()

		doReturn(centralProvider).when(gitHandler).getResourcesScm()
		doReturn(tenantProvider).when(gitHandler).getTenant()

		when(gitRepoFactory.create('argocd/cluster-resources', centralProvider))
			.thenReturn(sharedClusterRepo)
		when(gitRepoFactory.create('argocd/cluster-resources', tenantProvider))
			.thenReturn(sharedTenantRepo)

		RepositoryProvisioning provisioning = createProvisioning()

		assertThatThrownBy {
			provisioning.provideWorkspace()
		}.isInstanceOf(IllegalStateException)
			.hasMessageContaining('Dedicated Multi-Tenant mode requires separate local workspaces')
			.hasMessageContaining(sameRootDir)
	}

	@Test
	void 'clusterResourcesRepoTarget returns unprefixed target'() {
		config.application.namePrefix = 'testPrefix-'

		RepositoryProvisioning provisioning = createProvisioning()

		assertThat(provisioning.clusterResourcesRepoTarget()).isEqualTo('argocd/cluster-resources')
	}

	private RepositoryProvisioning createProvisioning() {
		return new RepositoryProvisioning(createDeploymentContext(),
			gitRepoFactory,
			gitHandler)
	}

	private DeploymentContext createDeploymentContext() {
		return new DeploymentContext(config,
			config.multiTenant.useDedicatedInstance ? DeploymentContext.TenantMode.MULTI_TENANT : DeploymentContext.TenantMode.SINGLE_TENANT,
			config.scm.scmManager?.internal ? DeploymentContext.DeploymentMode.INTERNAL : DeploymentContext.DeploymentMode.EXTERNAL,
			config.application.mirrorRepos,
			config.application.openshift ? DeploymentContext.ClusterDistribution.OPENSHIFT : DeploymentContext.ClusterDistribution.KUBERNETES)
	}

	private GitRepo createGitRepoSpy(String repoTarget,
		GitProvider gitProvider) {
		GitRepo gitRepo = spy(new GitRepo(createDeploymentContext(),
			gitProvider,
			repoTarget,
			new FileSystemUtils()))

		doNothing().when(gitRepo).cloneRepo()
		doNothing().when(gitRepo).initLocalRepoIfNeeded()
		doNothing().when(gitRepo).checkoutRemoteMainIfLocalMainMissing()
		doNothing().when(gitRepo).commitAndPush(any(String))

		return gitRepo
	}

	private static String createTempDir(String prefix) {
		return File.createTempDir(prefix, '').canonicalPath
	}
}