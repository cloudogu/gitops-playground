package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Path
import groovy.util.logging.Slf4j

/**
 * Holds ArgoCD-related repo initialization actions (cluster-resources + optional tenant bootstrap)
 * and encapsulates the initialization logic (single-instance vs. dedicated instance).*/
@Slf4j
class ArgoCDRepoSetup {

	final RepoInitializationAction clusterResources
	final RepoInitializationAction tenantBootstrap
	// may be null
	final List<RepoInitializationAction> allRepos

	private final DeploymentContext context
	private final FileSystemUtils fileSystemUtils

	private ArgoCDRepoSetup(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		RepoInitializationAction clusterResources,
		RepoInitializationAction tenantBootstrap,
		List<RepoInitializationAction> allRepos) {
		this.context = context
		this.fileSystemUtils = fileSystemUtils
		this.clusterResources = clusterResources
		this.tenantBootstrap = tenantBootstrap
		this.allRepos = allRepos
	}

	private Config getConfig() {
		return context.config
	}

	static ArgoCDRepoSetup create(DeploymentContext context, FileSystemUtils fileSystemUtils, GitRepoFactory repoFactory, GitHandler gitHandler) {
		RepoInitializationAction cluster
		RepoInitializationAction tenant
		List<RepoInitializationAction> all = []

		if (context.isMultiTenant()) {
			// Dedicated instance: tenant bootstrap (tenant provider) + cluster-resources (central provider)
			tenant = createRepoInitializationAction(context, repoFactory, gitHandler,
				'argocd/cluster-resources/apps/argocd/multiTenant/tenant',
				'argocd/cluster-resources',
				gitHandler.tenant)
			all.add(tenant)

			cluster = createRepoInitializationAction(context, repoFactory, gitHandler,
				'argocd/cluster-resources',
				'argocd/cluster-resources',
				gitHandler.central)
			all.add(cluster)

		} else {
			// Single instance: only cluster-resources (tenant provider)
			cluster = createRepoInitializationAction(context, repoFactory, gitHandler,
				'argocd/cluster-resources',
				'argocd/cluster-resources',
				gitHandler.tenant)
			all.add(cluster)
		}

		// Configure which subdirectories should be copied into the cluster-resources repo
		cluster.subDirsToCopy = determineClusterResourceSubDirs(context)

		return new ArgoCDRepoSetup(context, fileSystemUtils, cluster, tenant, all)
	}

	RepoLayout clusterRepoLayout() {
		new RepoLayout(clusterResources.repo.getAbsoluteLocalRepoTmpDir())
	}

	RepoLayout tenantRepoLayout() {
		if (tenantBootstrap == null) {
			throw new IllegalStateException("tenantBootstrap repo is not initialized (single-instance mode).")
		}
		new RepoLayout(tenantBootstrap.repo.getAbsoluteLocalRepoTmpDir())
	}

	void initLocalRepos() {
		allRepos.each { it.initLocalRepo() }
	}

	void prepareClusterResourcesRepo() {
		RepoLayout layout = clusterRepoLayout()

		if (config.features.argocd.operator) {
			fileSystemUtils.deleteDir(layout.helmDir())
		} else {
			fileSystemUtils.deleteDir(layout.operatorDir())
		}

		if (context.isMultiTenant()) {
			log.debug("Deleting unnecessary non dedicated instances folders from argocd repo: applications=${clusterRepoLayout().applicationsDir()}, projects=${clusterRepoLayout().projectsDir()}, tenant=${clusterRepoLayout().multiTenantDir()}/tenant")
			FileSystemUtils.deleteDir clusterRepoLayout().applicationsDir()
			FileSystemUtils.deleteDir clusterRepoLayout().projectsDir()
			fileSystemUtils.moveDirectoryMergeOverwrite(Path.of(clusterRepoLayout().multiTenantDir() + "/central"), Path.of(clusterRepoLayout().argocdRoot()))
			FileSystemUtils.deleteDir clusterRepoLayout().multiTenantDir()
		} else {
			fileSystemUtils.deleteDir(layout.multiTenantDir())
		}

		if (!config.application.netpols) {
			fileSystemUtils.deleteFile(layout.netpolFile())
		}
	}

	void commitAndPushAll(String message) {
		allRepos.each { it.repo.commitAndPush(message) }
	}

	private static Set<String> determineClusterResourceSubDirs(DeploymentContext context) {
		def config = context.config
		Set<String> clusterResourceSubDirs = new LinkedHashSet<>()

		clusterResourceSubDirs.add(RepoLayout.argocdSubdirRel())

		if (config.features.certManager.active) {
			clusterResourceSubDirs.add(RepoLayout.certManagerSubdirRel())
		}
		if (config.features.ingress.active) {
			clusterResourceSubDirs.add(RepoLayout.ingressSubdirRel())
		}
		if (config.jenkins.internal) {
			clusterResourceSubDirs.add(RepoLayout.jenkinsSubdirRel())
		}
		if (config.features.monitoring.active) {
			clusterResourceSubDirs.add(RepoLayout.monitoringSubdirRel())
		}
		if (context.isInternalScmManager()) {
			clusterResourceSubDirs.add(RepoLayout.scmManagerSubdirRel())
		}
		if (config.features.secrets.active) {
			clusterResourceSubDirs.add(RepoLayout.secretsSubdirRel())
			clusterResourceSubDirs.add(RepoLayout.vaultSubdirRel())
		}

		return clusterResourceSubDirs
	}

	private static RepoInitializationAction createRepoInitializationAction(DeploymentContext context,
		GitRepoFactory repoFactory,
		GitHandler gitHandler,
		String localSrcDir,
		String scmRepoTarget,
		GitProvider gitProvider) {
		new RepoInitializationAction(context,
			repoFactory.getRepo(scmRepoTarget, gitProvider),
			gitHandler,
			localSrcDir)
	}
}