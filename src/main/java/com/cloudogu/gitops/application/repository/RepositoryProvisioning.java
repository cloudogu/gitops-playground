package com.cloudogu.gitops.application.repository;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Prepares and makes the required GitOps repositories available during a GOP deployment.
 *
 * <p>This class is responsible for creating the shared {@link RepositoryWorkspace}, ensuring that
 * the required remote repositories exist, and cloning those repositories when they are already
 * available.
 *
 * <p>The main repository managed here is the {@code cluster-resources} repository. It contains the
 * generated GitOps resources that are consumed by ArgoCD, for example applications and projects.
 *
 * <p>In dedicated multi-tenant setups, two repository workspaces are required:
 *
 * <ul>
 *   <li>the cluster-resources repository in the central SCM-Manager, used by the central ArgoCD
 *       instance
 *   <li>the tenant bootstrap repository in the tenant SCM-Manager, used to bootstrap the tenant
 *       ArgoCD instance
 * </ul>
 *
 * <p>Both repositories can have the same logical repository target, but they must use separate
 * local workspaces because their templates may contain overlapping paths.
 *
 * <p>This class does not generate tool-specific resources. Tools write their files into the
 * prepared {@link RepositoryWorkspace}. Repository provisioning only coordinates repository
 * availability, local workspace preparation, and commit/push entry points.
 */
@Singleton
@Slf4j
public class RepositoryProvisioning {

public static final String CLUSTER_RESOURCES_REPO_TARGET = "argocd/cluster-resources";

private final GitRepoFactory gitRepoFactory;
private final GitHandler gitHandler;

@Getter @Setter private RepositoryWorkspace workspace;

@Getter @Setter private boolean repositoriesCloned;

public RepositoryProvisioning(GitRepoFactory gitRepoFactory, GitHandler gitHandler) {
	this.gitRepoFactory = gitRepoFactory;
	this.gitHandler = gitHandler;
}

public void prepare(DeploymentContext context) {
	provideWorkspace(context);

	if (mustWaitForInternalScmManagerDeployment(context)) {
	log.debug(
		"Preparing local repository workspace only because internal SCM-Manager is not deployed yet.");
	workspace.createLocalDirectories();
	return;
	}

	ensureRemoteRepositoriesExist();
	cloneRepositories();
}

public RepositoryWorkspace provideWorkspace(DeploymentContext context) {
	if (workspace != null) {
	return workspace;
	}

	if (context.isMultiTenant()) {
	workspace = createDedicatedInstanceWorkspace(context);
	} else {
	workspace = createSingleInstanceWorkspace(context);
	}

	return workspace;
}

public void ensureRemoteRepositoriesExist() {
	assertWorkspacePrepared();
	workspace.ensureRemoteRepositoriesExist();
}

public void cloneRepositories() {
	if (repositoriesCloned) {
	log.debug("Repositories already cloned. Skipping.");
	return;
	}

	assertWorkspacePrepared();
	try {
	workspace.cloneRepositories();
	} catch (Exception e) {
	throw new RuntimeException("Failed to clone repositories", e);
	}
	repositoriesCloned = true;
}

public void publishClusterResourcesRepositoryChanges(String toolName) {
	publishClusterResourcesRepositoryChanges(toolName, null);
}

public void publishClusterResourcesRepositoryChanges(String toolName, String message) {
	assertWorkspacePrepared();
	String actualMessage = message != null ? message : ("Update " + toolName + " resources");
	try {
	workspace.commitAndPushClusterResourcesChanges(actualMessage);
	} catch (Exception e) {
	throw new RuntimeException("Failed to publish cluster resources repository changes", e);
	}
}

public void publishClusterResourcesAndTenantBootstrapRepositoryChanges(String toolName) {
	publishClusterResourcesAndTenantBootstrapRepositoryChanges(toolName, null);
}

public void publishClusterResourcesAndTenantBootstrapRepositoryChanges(
	String toolName, String message) {
	assertWorkspacePrepared();
	String actualMessage = message != null ? message : ("Update " + toolName + " resources");
	try {
	workspace.commitAndPushClusterResourcesAndTenantBootstrapChanges(actualMessage);
	} catch (Exception e) {
	throw new RuntimeException(
		"Failed to publish cluster resources and tenant bootstrap repository changes", e);
	}
}

public String clusterResourcesRepoTarget() {
	return CLUSTER_RESOURCES_REPO_TARGET;
}

// Ownership of clusterResourcesRepository is handed off to the returned RepositoryWorkspace,
// which closes it in RepositoryWorkspace#close(). Sonar can't trace that across the boundary.
@SuppressWarnings("java:S2095")
private RepositoryWorkspace createSingleInstanceWorkspace(DeploymentContext context) {
	log.debug("Creating single-instance repository workspace.");

	GitRepo clusterResourcesRepository =
		gitRepoFactory.create(clusterResourcesRepoTarget(), gitHandler.getResourcesScm());

	return new RepositoryWorkspace(clusterResourcesRepository);
}

// Ownership of both GitRepo instances is handed off to the returned RepositoryWorkspace,
// which closes them in RepositoryWorkspace#close(). Sonar can't trace that across the boundary.
@SuppressWarnings("java:S2095")
private RepositoryWorkspace createDedicatedInstanceWorkspace(DeploymentContext context) {
	log.debug("Creating dedicated-instance repository workspace.");

	GitRepo clusterResourcesRepository =
		gitRepoFactory.create(clusterResourcesRepoTarget(), gitHandler.getResourcesScm());

	GitRepo tenantBootstrapRepository =
		gitRepoFactory.create(clusterResourcesRepoTarget(), gitHandler.getTenant());

	RepositoryWorkspace dedicatedWorkspace =
		new RepositoryWorkspace(clusterResourcesRepository, tenantBootstrapRepository);

	validateDedicatedWorkspace(dedicatedWorkspace);

	return dedicatedWorkspace;
}

private static void validateDedicatedWorkspace(RepositoryWorkspace workspace) {
	try {
	String clusterRoot = new File(workspace.clusterResourcesRootDir()).getCanonicalPath();
	String tenantRoot = new File(workspace.tenantBootstrapRootDir()).getCanonicalPath();

	if (clusterRoot.equals(tenantRoot)) {
		throw new IllegalStateException(
			"Dedicated Multi-Tenant mode requires separate local workspaces for "
				+ "central cluster-resources and tenant bootstrap repositories. Both resolved to: "
				+ clusterRoot);
	}
	} catch (IOException e) {
	throw new UncheckedIOException("Failed to resolve canonical path", e);
	}
}

private void assertWorkspacePrepared() {
	if (workspace == null) {
	throw new IllegalStateException(
		"Repository workspace must be prepared before repository changes can be published.");
	}
}

private static boolean mustWaitForInternalScmManagerDeployment(DeploymentContext context) {
	return context.isInternalScmManager();
}
}
