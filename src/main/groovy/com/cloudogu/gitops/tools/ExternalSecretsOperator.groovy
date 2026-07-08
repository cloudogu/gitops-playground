package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
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
@Order(400)
class ExternalSecretsOperator extends Tool implements ToolWithImage {

	static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/external-secrets/templates/values.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = 'argocd/cluster-resources'
	private static final String TOOL_NAME = 'external-secrets'
	private static final String RELEASE_NAME = 'external-secrets'
	private static final String EXTERNAL_SECRETS_APP_PATH = 'apps/external-secrets'

	String namespace
	final K8sClient k8sClient

	ExternalSecretsOperator(FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler) {
		this.deployer = deployer
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.secrets.active
	}

	@Override
	protected void prepare() {
		this.namespace = activeNamespace(context)
	}

	@Override
	protected String activeNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}${context.config.features.secrets.namespace}"
	}

	@Override
	void enable() {
		prepareExternalSecretsApp(repositoryWorkspace.clusterResourcesRepository)

		def helmConfig = config.features.secrets.externalSecrets.helm

		deployHelmChart(TOOL_NAME,
			RELEASE_NAME,
			namespace,
			helmConfig,
			HELM_VALUES_PATH,
			context)

		repositoryWorkspace.commitAndPushClusterResourcesChanges("Update ${TOOL_NAME} GitOps resources")
	}

	private void prepareExternalSecretsApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing external-secrets repository content in ${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, EXTERNAL_SECRETS_APP_PATH))
	}
}