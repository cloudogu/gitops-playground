package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.helm.HelmClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.TestGitProvider;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.tools.core.argocd.mode.DeploymentModeFactory;
import com.cloudogu.gitops.utils.CommandExecutorForTest;
import com.cloudogu.gitops.utils.FileSystemUtils;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

class ArgoCDForTest extends ArgoCD {

	final Config cfg;
	final GitProvider tenantProvider;
	final GitProvider centralProvider;
	final GitHandler gitHandler;
	final RepositoryWorkspace repositoryWorkspace;

	GitRepo clusterResourcesRepo;
	GitRepo tenantBootstrapRepo;

	static ArgoCDForTest newWithAutoProviders(
		Config cfg,
		K8sClient k8sClient,
		CommandExecutorForTest helmCommands) {
		Map<String, GitProvider> providers = TestGitProvider.buildProviders(cfg);

		GitProvider tenantProvider = providers.get("tenant");
		GitProvider centralProvider = providers.get("central");

		ArgoCDTestContext testContext = createTestContext(cfg, tenantProvider, centralProvider);

		return new ArgoCDForTest(
			cfg,
			k8sClient,
			helmCommands,
			tenantProvider,
			centralProvider,
			testContext
		);
	}

	private static ArgoCDTestContext createTestContext(
		Config cfg,
		GitProvider tenantProvider,
		GitProvider centralProvider) {
		TestGitRepoFactory repoFactory = new TestGitRepoFactory(cfg, new FileSystemUtils());

		GitProvider clusterResourcesProvider = Boolean.TRUE.equals(cfg.getMultiTenant().getUseDedicatedInstance())
			? centralProvider
			: tenantProvider;

		GitRepo clusterResourcesRepo = repoFactory.create("argocd/cluster-resources", clusterResourcesProvider);
		stubCommitAndPush(clusterResourcesRepo);

		RepositoryWorkspace repositoryWorkspace;
		GitRepo tenantBootstrapRepo = null;

		if (Boolean.TRUE.equals(cfg.getMultiTenant().getUseDedicatedInstance())) {
			/*
			 * Test-only workspace separation:
			 *
			 * In the real dedicated multi-tenant setup, the central cluster-resources repo
			 * and the tenant bootstrap repo use the same logical repo target in different
			 * SCM-Manager instances.
			 *
			 * TestGitRepoFactory derives the local workspace from the repo target only.
			 * Therefore both GitRepo objects would otherwise point to the same local directory
			 * and tenant bootstrap templates would overwrite central bootstrap templates.
			 */
			tenantBootstrapRepo = repoFactory.create(
				"argocd/tenant-bootstrap-cluster-resources",
				tenantProvider
			);
			stubCommitAndPush(tenantBootstrapRepo);

			repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo, tenantBootstrapRepo);
		} else {
			repositoryWorkspace = new RepositoryWorkspace(clusterResourcesRepo);
		}

		GitHandler gitHandler = new GitHandlerForTests(tenantProvider, centralProvider);

		return new ArgoCDTestContext(
			gitHandler,
			repositoryWorkspace,
			clusterResourcesRepo,
			tenantBootstrapRepo
		);
	}

	private static void stubCommitAndPush(GitRepo repository) {
		try {
			doNothing().when(repository).commitAndPush(any(String.class));
		} catch (GitAPIException e) {
			throw new IllegalStateException("Failed to configure GitRepo test spy", e);
		}
	}

	ArgoCDForTest(
		Config cfg,
		K8sClient k8sClient,
		CommandExecutorForTest helmCommands,
		GitProvider tenantProvider,
		GitProvider centralProvider,
		ArgoCDTestContext testContext) {
		super(
			k8sClient,
			new HelmClient(helmCommands),
			new FileSystemUtils(),
			testContext.gitHandler(),
			new DeploymentModeFactory(),
			new ArgoCDToolConfigMapper(cfg)
		);

		this.cfg = cfg;
		this.tenantProvider = tenantProvider;
		this.centralProvider = centralProvider;
		this.gitHandler = testContext.gitHandler();
		this.repositoryWorkspace = testContext.repositoryWorkspace();
		this.clusterResourcesRepo = testContext.clusterResourcesRepo();
		this.tenantBootstrapRepo = testContext.tenantBootstrapRepo();

		mockPrefixActiveNamespaces(cfg);
	}

	private static void mockPrefixActiveNamespaces(Config config) {
		String prefix = config.getApplication().getNamePrefix() == null
			? ""
			: config.getApplication().getNamePrefix();

		Config.ApplicationSchema.NamespaceSchema namespaces = config.getApplication().getNamespaces();
		namespaces.setDedicatedNamespaces(
			namespaces.getDedicatedNamespaces().stream()
				.map(namespace -> prefix + namespace)
				.collect(Collectors.toCollection(LinkedHashSet::new))
		);
		namespaces.setTenantNamespaces(
			namespaces.getTenantNamespaces().stream()
				.map(namespace -> prefix + namespace)
				.collect(Collectors.toCollection(LinkedHashSet::new))
		);
	}

	boolean execute() {
		return super.execute(new ContextBuilder(cfg).build(), repositoryWorkspace);
	}

	GitRepo getClusterResourcesRepo() {
		return clusterResourcesRepo;
	}

	ArgoCDRepoLayout getClusterRepoLayout() {
		return getRepoSetup().clusterRepoLayout();
	}

	ArgoCDRepoLayout getTenantRepoLayout() {
		return getRepoSetup().tenantRepoLayout();
	}

	private record ArgoCDTestContext(
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace,
		GitRepo clusterResourcesRepo,
		GitRepo tenantBootstrapRepo) {
	}
}
