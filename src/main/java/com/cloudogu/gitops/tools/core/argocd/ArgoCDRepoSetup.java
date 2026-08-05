package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter;
import com.cloudogu.gitops.utils.FileSystemUtils;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.TemplateModel;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public class ArgoCDRepoSetup {

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = "argocd/cluster-resources";
	private static final String TENANT_BOOTSTRAP_SOURCE_DIR = "argocd/cluster-resources/apps/argocd/multiTenant/tenant";
	private static final String ARGOCD_APP_PATH = ArgoCDRepoLayout.argocdSubdirRel();

	private final DeploymentContext context;
	private final FileSystemUtils fileSystemUtils;
	private final GitHandler gitHandler;
	private final RepositoryWorkspace repositoryWorkspace;

	public static ArgoCDRepoSetup create(
		DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace) {
		return new ArgoCDRepoSetup(context, fileSystemUtils, gitHandler, repositoryWorkspace);
	}

	private Config getConfig() {
		return context.getConfig();
	}

	public ArgoCDRepoLayout clusterRepoLayout() {
		return new ArgoCDRepoLayout(repositoryWorkspace.clusterResourcesRootDir());
	}

	public ArgoCDRepoLayout tenantRepoLayout() {
		if (!repositoryWorkspace.hasTenantBootstrapRepository()) {
			throw new IllegalStateException("tenantBootstrap repo is not initialized in single-instance mode.");
		}

		return new ArgoCDRepoLayout(repositoryWorkspace.tenantBootstrapRootDir());
	}

	public void prepareRepositories() {
		validateRepositoryWorkspace();

		prepareClusterResourcesRepo();

		if (context.isMultiTenant()) {
			prepareTenantBootstrapRepo();
		}
	}

	private void validateRepositoryWorkspace() {
		if (context.isSingleTenant()) {
			return;
		}

		if (!repositoryWorkspace.hasTenantBootstrapRepository()) {
			throw new IllegalStateException("Dedicated Multi-Tenant mode requires a tenant bootstrap repository.");
		}

		try {
			String clusterRoot = new File(repositoryWorkspace.clusterResourcesRootDir()).getCanonicalPath();
			String tenantRoot = new File(repositoryWorkspace.tenantBootstrapRootDir()).getCanonicalPath();

			if (clusterRoot.equals(tenantRoot)) {
				throw new IllegalStateException("Dedicated Multi-Tenant mode requires separate local workspaces for " + "central cluster-resources and tenant bootstrap repositories. " + "Both resolved to: " + clusterRoot);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void prepareClusterResourcesRepo() {
		GitRepo clusterResourcesRepo = repositoryWorkspace.getClusterResourcesRepository();

		log.debug(
			"Preparing ArgoCD repository content in {} from {}/{}",
			clusterResourcesRepo.getRepoTarget(),
			CLUSTER_RESOURCES_SOURCE_DIR,
			ARGOCD_APP_PATH
		);

		clusterResourcesRepo.copyDirectoryContents(
			CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, ARGOCD_APP_PATH)
		);

		clusterResourcesRepo.replaceTemplates(buildTemplateValues(clusterResourcesRepo));

		prepareClusterResourcesLayout();
	}

	private void prepareTenantBootstrapRepo() {
		GitRepo tenantBootstrapRepo = repositoryWorkspace.tenantBootstrapRepositoryOrFail();

		log.debug(
			"Preparing tenant bootstrap repo {} from {}",
			tenantBootstrapRepo.getRepoTarget(),
			TENANT_BOOTSTRAP_SOURCE_DIR
		);

		tenantBootstrapRepo.copyDirectoryContents(TENANT_BOOTSTRAP_SOURCE_DIR, allowAllFilter());

		tenantBootstrapRepo.replaceTemplates(buildTemplateValues(tenantBootstrapRepo));
	}

	private void prepareClusterResourcesLayout() {
		ArgoCDRepoLayout layout = clusterRepoLayout();

		if (getConfig().getFeatures().getArgocd().getOperator()) {
			FileSystemUtils.deleteDir(layout.helmDir());
		} else {
			FileSystemUtils.deleteDir(layout.operatorDir());
		}

		if (context.isMultiTenant()) {
			log.debug(
				"Deleting unnecessary non dedicated instances folders from argocd repo: " + "applications={}, projects={}, tenant={}/tenant",
				layout.applicationsDir(),
				layout.projectsDir(),
				layout.multiTenantDir()
			);

			FileSystemUtils.deleteDir(layout.applicationsDir());
			FileSystemUtils.deleteDir(layout.projectsDir());

			fileSystemUtils.moveDirectoryMergeOverwrite(
				Path.of(layout.multiTenantDir(), "central"),
				Path.of(layout.argocdRoot())
			);

			FileSystemUtils.deleteDir(layout.multiTenantDir());
		} else {
			FileSystemUtils.deleteDir(layout.multiTenantDir());
		}

		if (!getConfig().getApplication().getNetpols()) {
			FileSystemUtils.deleteFile(layout.netpolFile());
		}
	}

	private Map<String, Object> buildTemplateValues(GitRepo repo) {
		Map<String, Object> values = new HashMap<>();
		values.put("tenantName", getConfig().getApplication().getTenantName());

		Map<String, Object> argocd = new HashMap<>();
		try {
			argocd.put(
				"host", getConfig().getFeatures().getArgocd().getUrl() != null && !getConfig().getFeatures()
				                                                                              .getArgocd()
				                                                                              .getUrl()
				                                                                              .isEmpty() ? new URL(
					getConfig().getFeatures()
					           .getArgocd()
					           .getUrl()).getHost() : ""
			);
		} catch (MalformedURLException e) {
			throw new UncheckedIOException(e);
		}
		values.put("argocd", argocd);

		Map<String, Object> scm = new HashMap<>();
		scm.put("baseUrl", repo.getGitProvider().getUrl());
		scm.put("host", repo.getGitProvider().getHost());
		scm.put("protocol", repo.getGitProvider().getProtocol());
		scm.put("repoUrl", repo.getGitProvider().repoPrefix());
		scm.put("centralScmUrl", gitHandler.getCentral() != null ? gitHandler.getCentral().repoPrefix() : "");
		values.put("scm", scm);

		values.put("config", getConfig());

		try {
			TemplateModel statics = new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build()
			                                                                                                         .getStaticModels();
			values.put("statics", statics);
		} catch (Exception e) {
			throw new RuntimeException("Failed to expose freemarker statics model", e);
		}

		return values;
	}

	private static FileFilter allowAllFilter() {
		return file -> true;
	}
}
