package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class ArgoCDRepoSetup {

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = 'argocd/cluster-resources'
	private static final String TENANT_BOOTSTRAP_SOURCE_DIR = 'argocd/cluster-resources/apps/argocd/multiTenant/tenant'

	private final Config config
	private final FileSystemUtils fileSystemUtils
	private final GitHandler gitHandler
	private final RepositoryWorkspace repositoryWorkspace

	private ArgoCDRepoSetup(
		Config config,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace
	) {
		this.config = config
		this.fileSystemUtils = fileSystemUtils
		this.gitHandler = gitHandler
		this.repositoryWorkspace = repositoryWorkspace
	}

	static ArgoCDRepoSetup create(
		Config config,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace
	) {
		new ArgoCDRepoSetup(
			config,
			fileSystemUtils,
			gitHandler,
			repositoryWorkspace
		)
	}

	ArgoCDRepoLayout clusterRepoLayout() {
		new ArgoCDRepoLayout(repositoryWorkspace.clusterResourcesRootDir())
	}

	ArgoCDRepoLayout tenantRepoLayout() {
		if (!repositoryWorkspace.hasTenantBootstrapRepository()) {
			throw new IllegalStateException("tenantBootstrap repo is not initialized in single-instance mode.")
		}

		new ArgoCDRepoLayout(repositoryWorkspace.tenantBootstrapRootDir())
	}

	void prepareRepositories() {
		prepareClusterResourcesRepo()

		if (config.multiTenant.useDedicatedInstance) {
			prepareTenantBootstrapRepo()
		}
	}

	private void prepareClusterResourcesRepo() {
		GitRepo clusterResourcesRepo = repositoryWorkspace.clusterResourcesRepository

		Set<String> subDirsToCopy = determineLegacyClusterResourceSubDirs(config)

		log.debug(
			"Preparing cluster-resources repo ${clusterResourcesRepo.repoTarget} from ${CLUSTER_RESOURCES_SOURCE_DIR} with subdirs: ${subDirsToCopy}"
		)

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			createSubdirFilter(CLUSTER_RESOURCES_SOURCE_DIR, subDirsToCopy)
		)

		clusterResourcesRepo.replaceTemplates(
			buildTemplateValues(clusterResourcesRepo)
		)

		prepareClusterResourcesLayout()
	}

	private void prepareTenantBootstrapRepo() {
		GitRepo tenantBootstrapRepo = repositoryWorkspace.tenantBootstrapRepositoryOrFail()

		log.debug(
			"Preparing tenant bootstrap repo ${tenantBootstrapRepo.repoTarget} from ${TENANT_BOOTSTRAP_SOURCE_DIR}"
		)

		tenantBootstrapRepo.copyDirectoryContents(
			TENANT_BOOTSTRAP_SOURCE_DIR,
			allowAllFilter()
		)

		tenantBootstrapRepo.replaceTemplates(
			buildTemplateValues(tenantBootstrapRepo)
		)
	}

	private void prepareClusterResourcesLayout() {
		ArgoCDRepoLayout layout = clusterRepoLayout()

		if (config.features.argocd.operator) {
			fileSystemUtils.deleteDir(layout.helmDir())
		} else {
			fileSystemUtils.deleteDir(layout.operatorDir())
		}

		if (config.multiTenant.useDedicatedInstance) {
			log.debug(
				"Deleting unnecessary non dedicated instances folders from argocd repo: " +
					"applications=${layout.applicationsDir()}, " +
					"projects=${layout.projectsDir()}, " +
					"tenant=${layout.multiTenantDir()}/tenant"
			)

			FileSystemUtils.deleteDir(layout.applicationsDir())
			FileSystemUtils.deleteDir(layout.projectsDir())

			fileSystemUtils.moveDirectoryMergeOverwrite(
				Path.of(layout.multiTenantDir(), 'central'),
				Path.of(layout.argocdRoot())
			)

			FileSystemUtils.deleteDir(layout.multiTenantDir())
		} else {
			fileSystemUtils.deleteDir(layout.multiTenantDir())
		}

		if (!config.application.netpols) {
			fileSystemUtils.deleteFile(layout.netpolFile())
		}
	}

	private static Set<String> determineLegacyClusterResourceSubDirs(Config config) {
		Set<String> clusterResourceSubDirs = new LinkedHashSet<>()

		// ArgoCD remains owned by ArgoCDRepoSetup.
		clusterResourceSubDirs.add(ArgoCDRepoLayout.argocdSubdirRel())

		// Transitional behavior:
		// These tool directories are still copied here to preserve the current behavior.
		// In the target architecture each tool prepares its own apps/<tool> directory.
		if (config.features.certManager.active) {
			clusterResourceSubDirs.add(ArgoCDRepoLayout.certManagerSubdirRel())
		}

		if (config.features.ingress.active) {
			clusterResourceSubDirs.add(ArgoCDRepoLayout.ingressSubdirRel())
		}

		if (config.jenkins.internal) {
			clusterResourceSubDirs.add(ArgoCDRepoLayout.jenkinsSubdirRel())
		}

		if (config.features.monitoring.active) {
			clusterResourceSubDirs.add(ArgoCDRepoLayout.monitoringSubdirRel())
		}

		if (config.features.secrets.active) {
			clusterResourceSubDirs.add(ArgoCDRepoLayout.secretsSubdirRel())
			clusterResourceSubDirs.add(ArgoCDRepoLayout.vaultSubdirRel())
		}

		return clusterResourceSubDirs
	}

	private Map<String, Object> buildTemplateValues(GitRepo repo) {
		[
			tenantName: config.application.tenantName,
			argocd    : [
				host: config.features.argocd.url ?
				      new URL(config.features.argocd.url).host :
				      ''
			],
			scm       : [
				baseUrl      : repo.gitProvider.url,
				host         : repo.gitProvider.host,
				protocol     : repo.gitProvider.protocol,
				repoUrl      : repo.gitProvider.repoPrefix(),
				centralScmUrl: gitHandler.central?.repoPrefix() ?: ''
			],
			config    : config,
			statics   : new DefaultObjectWrapperBuilder(
				freemarker.template.Configuration.VERSION_2_3_32
			).build().getStaticModels()
		] as Map<String, Object>
	}

	private static FileFilter allowAllFilter() {
		return { File f -> true } as FileFilter
	}

	private static FileFilter createSubdirFilter(String copyFromDirectory, Set<String> subDirsToCopy) {
		if (!subDirsToCopy || subDirsToCopy.isEmpty()) {
			return allowAllFilter()
		}

		File srcRoot = new File(copyFromDirectory).canonicalFile

		Set<String> prefixes = subDirsToCopy.collect { String s ->
			String norm = s.replace('\\', '/')
			norm = norm.replaceAll('^/+', '').replaceAll('/+$', '')
			norm + '/'
		} as Set<String>

		Set<String> templateIncludePrefixes = [
			'apps/argocd/argocd/templates/'
		] as Set<String>

		return { File f ->
			File canon = f.canonicalFile
			String rel = srcRoot.toURI().relativize(canon.toURI()).toString()
			rel = rel.replace('\\', '/')

			if (rel == '' || rel == '.') {
				return true
			}

			boolean isDir = f.isDirectory()
			String relDir = rel.endsWith('/') ? rel : rel + '/'

			if (templateIncludePrefixes.any { String p ->
				(isDir ? relDir : rel).startsWith(p)
			}) {
				return true
			}

			if (rel.startsWith('apps/') && relDir.contains('/templates/')) {
				return false
			}

			if (isDir) {
				return prefixes.any { String p ->
					relDir == p || relDir.startsWith(p) || p.startsWith(relDir)
				}
			}

			prefixes.any { String p ->
				rel.startsWith(p)
			}
		} as FileFilter
	}
}