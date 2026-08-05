package com.cloudogu.gitops.application.repository;

import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Represents the prepared local GitOps repository workspace used during a GOP deployment.
 *
 * <p>The workspace provides access to the local checkout of the {@code cluster-resources}
 * repository. This repository contains the generated GitOps resources that are consumed by ArgoCD,
 * for example applications and projects.
 *
 * <p>In single-instance setups only the {@code cluster-resources} repository is required. In
 * dedicated multi-tenant setups an additional tenant bootstrap repository is required. This second
 * repository contains the bootstrap resources for the tenant ArgoCD instance, while the regular
 * {@code cluster-resources} repository is used by the central ArgoCD instance to bootstrap/manage
 * tenant resources.
 *
 * <p>This class does not decide which repositories are needed. That decision belongs to {@link
 * RepositoryProvisioning}. This class only exposes the prepared repositories and the directory
 * structure that tools can write to.
 */
@Slf4j
public class RepositoryWorkspace implements AutoCloseable {

	@Getter
	private final GitRepo clusterResourcesRepository;

	@Getter
	private final GitRepo tenantBootstrapRepository;

	private boolean remoteRepositoriesEnsured;

	public RepositoryWorkspace(GitRepo clusterResourcesRepository) {
		this(clusterResourcesRepository, null);
	}

	public RepositoryWorkspace(GitRepo clusterResourcesRepository, GitRepo tenantBootstrapRepository) {
		this.clusterResourcesRepository = clusterResourcesRepository;
		this.tenantBootstrapRepository = tenantBootstrapRepository;
	}

	public boolean hasTenantBootstrapRepository() {
		return tenantBootstrapRepository != null;
	}

	/**
	 * Returns the tenant bootstrap repository or fails if this workspace was created for a
	 * single-instance setup.
	 */
	public GitRepo tenantBootstrapRepositoryOrFail() {
		if (tenantBootstrapRepository == null) {
			throw new IllegalStateException("Tenant bootstrap repository is not available in single-instance mode.");
		}

		return tenantBootstrapRepository;
	}

	/**
	 * Ensures that all remote repositories represented by this workspace exist.
	 *
	 * <p>The decision which repositories are part of this workspace still belongs to {@link
	 * RepositoryProvisioning}. This method only ensures the already prepared repository handles.
	 */
	public void ensureRemoteRepositoriesExist() {
		if (remoteRepositoriesEnsured) {
			log.debug("Remote repositories already ensured. Skipping.");
			return;
		}

		log.debug("Ensuring cluster resources repository. repoTarget='{}'", clusterResourcesRepository.getRepoTarget());

		ensureRepositoryExists(
			clusterResourcesRepository.getGitProvider(),
			clusterResourcesRepository.getRepoTarget(),
			"GitOps repo for basic cluster-resources"
		);

		if (hasTenantBootstrapRepository()) {
			log.debug(
				"Ensuring tenant bootstrap repository. repoTarget='{}'",
				tenantBootstrapRepositoryOrFail().getRepoTarget()
			);

			ensureRepositoryExists(
				tenantBootstrapRepositoryOrFail().getGitProvider(),
				tenantBootstrapRepositoryOrFail().getRepoTarget(),
				"GitOps repo for tenant bootstrap resources"
			);
		}

		remoteRepositoriesEnsured = true;
	}

	public void createLocalDirectories() {
		Path.of(clusterResourcesRootDir()).toFile().mkdirs();
		Path.of(clusterResourcesAppsDir()).toFile().mkdirs();
		Path.of(clusterResourcesArgoCdDir()).toFile().mkdirs();
		Path.of(clusterResourcesApplicationsDir()).toFile().mkdirs();
		Path.of(clusterResourcesProjectsDir()).toFile().mkdirs();

		if (hasTenantBootstrapRepository()) {
			Path.of(tenantBootstrapRootDir()).toFile().mkdirs();
			Path.of(tenantBootstrapAppsDir()).toFile().mkdirs();
			Path.of(tenantBootstrapArgoCdDir()).toFile().mkdirs();
			Path.of(tenantBootstrapApplicationsDir()).toFile().mkdirs();
			Path.of(tenantBootstrapProjectsDir()).toFile().mkdirs();
		}
	}

	public void cloneRepositories() throws GitAPIException {
		clusterResourcesRepository.cloneRepo();

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().cloneRepo();
		}
	}

	/**
	 * Initializes local repositories when they cannot be cloned yet.
	 *
	 * <p>This is needed when GOP deploys an internal SCM-Manager first. In that case, the remote
	 * repositories are not available at the beginning of the deployment, but tools still need local
	 * directories to write their generated resources.
	 */
	public void initLocalRepositoriesIfNeeded() throws GitAPIException {
		clusterResourcesRepository.initLocalRepoIfNeeded();

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().initLocalRepoIfNeeded();
		}
	}

	public String clusterResourcesRootDir() {
		return clusterResourcesRepository.getAbsoluteLocalRepoTmpDir();
	}

	public String clusterResourcesAppsDir() {
		return Path.of(clusterResourcesRootDir(), "apps").toString();
	}

	public String clusterResourcesArgoCdDir() {
		return Path.of(clusterResourcesAppsDir(), "argocd").toString();
	}

	public String clusterResourcesApplicationsDir() {
		return Path.of(clusterResourcesArgoCdDir(), "applications").toString();
	}

	public String clusterResourcesProjectsDir() {
		return Path.of(clusterResourcesArgoCdDir(), "projects").toString();
	}

	public String tenantBootstrapRootDir() {
		return tenantBootstrapRepositoryOrFail().getAbsoluteLocalRepoTmpDir();
	}

	public String tenantBootstrapAppsDir() {
		return Path.of(tenantBootstrapRootDir(), "apps").toString();
	}

	public String tenantBootstrapArgoCdDir() {
		return Path.of(tenantBootstrapAppsDir(), "argocd").toString();
	}

	public String tenantBootstrapApplicationsDir() {
		return Path.of(tenantBootstrapArgoCdDir(), "applications").toString();
	}

	public String tenantBootstrapProjectsDir() {
		return Path.of(tenantBootstrapArgoCdDir(), "projects").toString();
	}

	public void commitAndPushClusterResourcesAndTenantBootstrapChanges(String message) throws GitAPIException {
		commitAndPushClusterResourcesChanges(message);

		if (hasTenantBootstrapRepository()) {
			commitAndPushTenantBootstrapChanges(message);
		}
	}

	public void commitAndPushTenantBootstrapChanges(String message) throws GitAPIException {
		tenantBootstrapRepositoryOrFail().commitAndPush(message);
	}

	public void commitAndPushClusterResourcesChanges(String message) throws GitAPIException {
		log.debug(message);
		clusterResourcesRepository.commitAndPush(message);
	}

	/**
	 * Aligns locally initialized repositories with the remote main branch if it already exists.
	 */
	public void alignWithRemoteMainIfPresent() throws GitAPIException, IOException {
		clusterResourcesRepository.checkoutRemoteMainIfLocalMainMissing();

		if (hasTenantBootstrapRepository()) {
			tenantBootstrapRepositoryOrFail().checkoutRemoteMainIfLocalMainMissing();
		}
	}

	private static void ensureRepositoryExists(GitProvider gitProvider, String repoTarget, String description) {
		gitProvider.createRepository(repoTarget, description, true);
	}

	@Override
	public void close() {
		try {
			if (clusterResourcesRepository != null) {
				clusterResourcesRepository.close();
			}
		} catch (Exception e) {
			log.warn("Error closing cluster resources repository", e);
		}
		try {
			if (tenantBootstrapRepository != null) {
				tenantBootstrapRepository.close();
			}
		} catch (Exception e) {
			log.warn("Error closing tenant bootstrap repository", e);
		}
	}
}
