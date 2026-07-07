package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Path
import groovy.util.logging.Slf4j

import freemarker.template.DefaultObjectWrapperBuilder

@Slf4j
class ArgoCDRepoSetup {

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = 'argocd/cluster-resources'
	private static final String TENANT_BOOTSTRAP_SOURCE_DIR = 'argocd/cluster-resources/apps/argocd/multiTenant/tenant'
	private static final String ARGOCD_APP_PATH = ArgoCDRepoLayout.argocdSubdirRel()

	private final DeploymentContext context
	private final FileSystemUtils fileSystemUtils
	private final GitHandler gitHandler
	private final RepositoryWorkspace repositoryWorkspace

	private ArgoCDRepoSetup(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace) {
		this.context = context
		this.fileSystemUtils = fileSystemUtils
		this.gitHandler = gitHandler
		this.repositoryWorkspace = repositoryWorkspace
	}

	static ArgoCDRepoSetup create(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace) {
		return new ArgoCDRepoSetup(context,
			fileSystemUtils,
			gitHandler,
			repositoryWorkspace)
	}

	private Config getConfig() {
		return context.config
	}

	ArgoCDRepoLayout clusterRepoLayout() {
		return new ArgoCDRepoLayout(repositoryWorkspace.clusterResourcesRootDir())
	}

	ArgoCDRepoLayout tenantRepoLayout() {
		if (!repositoryWorkspace.hasTenantBootstrapRepository()) {
			throw new IllegalStateException('tenantBootstrap repo is not initialized in single-instance mode.')
		}

		return new ArgoCDRepoLayout(repositoryWorkspace.tenantBootstrapRootDir())
	}

	void prepareRepositories() {
		validateRepositoryWorkspace()

		prepareClusterResourcesRepo()

		if (context.isMultiTenant()) {
			prepareTenantBootstrapRepo()
		}
	}

	private void validateRepositoryWorkspace() {
		if (context.isSingleTenant()) {
			return
		}

		if (!repositoryWorkspace.hasTenantBootstrapRepository()) {
			throw new IllegalStateException('Dedicated Multi-Tenant mode requires a tenant bootstrap repository.')
		}

		String clusterRoot = new File(repositoryWorkspace.clusterResourcesRootDir()).canonicalPath
		String tenantRoot = new File(repositoryWorkspace.tenantBootstrapRootDir()).canonicalPath

		if (clusterRoot == tenantRoot) {
			throw new IllegalStateException('Dedicated Multi-Tenant mode requires separate local workspaces for ' +
				'central cluster-resources and tenant bootstrap repositories. ' +
				"Both resolved to: ${clusterRoot}")
		}
	}

	private void prepareClusterResourcesRepo() {
		GitRepo clusterResourcesRepo = repositoryWorkspace.clusterResourcesRepository

		log.debug("Preparing ArgoCD repository content in ${clusterResourcesRepo.repoTarget} from ${CLUSTER_RESOURCES_SOURCE_DIR}/${ARGOCD_APP_PATH}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, ARGOCD_APP_PATH))

		clusterResourcesRepo.replaceTemplates(buildTemplateValues(clusterResourcesRepo))

		prepareClusterResourcesLayout()
	}

	private void prepareTenantBootstrapRepo() {
		GitRepo tenantBootstrapRepo = repositoryWorkspace.tenantBootstrapRepositoryOrFail()

		log.debug("Preparing tenant bootstrap repo ${tenantBootstrapRepo.repoTarget} from ${TENANT_BOOTSTRAP_SOURCE_DIR}")

		tenantBootstrapRepo.copyDirectoryContents(TENANT_BOOTSTRAP_SOURCE_DIR,
			allowAllFilter())

		tenantBootstrapRepo.replaceTemplates(buildTemplateValues(tenantBootstrapRepo))
	}

	private void prepareClusterResourcesLayout() {
		ArgoCDRepoLayout layout = clusterRepoLayout()

		if (config.features.argocd.operator) {
			fileSystemUtils.deleteDir(layout.helmDir())
		} else {
			fileSystemUtils.deleteDir(layout.operatorDir())
		}

		if (context.isMultiTenant()) {
			log.debug('Deleting unnecessary non dedicated instances folders from argocd repo: ' +
				"applications=${layout.applicationsDir()}, " +
				"projects=${layout.projectsDir()}, " +
				"tenant=${layout.multiTenantDir()}/tenant")

			fileSystemUtils.deleteDir(layout.applicationsDir())
			fileSystemUtils.deleteDir(layout.projectsDir())

			fileSystemUtils.moveDirectoryMergeOverwrite(Path.of(layout.multiTenantDir(), 'central'),
				Path.of(layout.argocdRoot()))

			fileSystemUtils.deleteDir(layout.multiTenantDir())
		} else {
			fileSystemUtils.deleteDir(layout.multiTenantDir())
		}

		if (!config.application.netpols) {
			fileSystemUtils.deleteFile(layout.netpolFile())
		}
	}

	private Map<String, Object> buildTemplateValues(GitRepo repo) {
		return [tenantName: config.application.tenantName,
		        argocd    : [host: config.features.argocd.url ? new URL(config.features.argocd.url).host : ''],
		        scm       : [baseUrl      : repo.gitProvider.url,
		                     host         : repo.gitProvider.host,
		                     protocol     : repo.gitProvider.protocol,
		                     repoUrl      : repo.gitProvider.repoPrefix(),
		                     centralScmUrl: gitHandler.central?.repoPrefix() ?: ''],
		        config    : config,
		        statics   : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()] as Map<String, Object>
	}

	private static FileFilter allowAllFilter() {
		return { File f -> true } as FileFilter
	}
}