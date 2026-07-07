package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.common.ToolWithImage
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter
import com.cloudogu.gitops.utils.FileSystemUtils

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(160)
class CertManager extends Tool implements ToolWithImage {

	static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/cert-manager/templates/values.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = 'argocd/cluster-resources'
	private static final String TOOL_NAME = 'cert-manager'
	private static final String CERT_MANAGER_APP_PATH = 'apps/cert-manager'

	final K8sClient k8sClient
	String namespace

	private final RepositoryProvisioning repositoryProvisioning

	CertManager(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		RepositoryProvisioning repositoryProvisioning) {
		this.deployer = deployer
		this.context = context
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
		this.repositoryProvisioning = repositoryProvisioning
		this.namespace = "${config.application.namePrefix}${config.features.certManager.namespace}"
	}

	@Override
	boolean isEnabled() {
		return config.features.certManager.active
	}

	@Override
	void enable() {
		GitRepo clusterResourcesRepo = clusterResourcesRepository()
		prepareCertManagerApp(clusterResourcesRepo)

		deployHelmChart(TOOL_NAME,
			TOOL_NAME,
			namespace,
			config.features.certManager.helm,
			HELM_VALUES_PATH,
			context)
	}

	private GitRepo clusterResourcesRepository() {
		RepositoryWorkspace workspace = repositoryProvisioning.provideWorkspace()
		return workspace.clusterResourcesRepository
	}

	private void prepareCertManagerApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing cert-manager repository content in ${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, CERT_MANAGER_APP_PATH))
	}
}