package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
@Singleton
@Order(160)
class CertManager extends Tool {

	static final String HELM_VALUES_PATH =
		'argocd/cluster-resources/apps/cert-manager/templates/values.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR =
		'argocd/cluster-resources'
	private static final String TOOL_NAME = 'cert-manager'
	private static final String CERT_MANAGER_APP_PATH =
		'apps/cert-manager'

	private final HelmToolDeployer helmToolDeployer
	private final ImagePullSecretCreator imagePullSecretCreator

	String namespace

	CertManager(HelmToolDeployer helmToolDeployer,
		ImagePullSecretCreator imagePullSecretCreator) {
		this.helmToolDeployer = helmToolDeployer
		this.imagePullSecretCreator = imagePullSecretCreator
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.certManager.active
	}

	@Override
	protected void preDeploy() {
		this.namespace = resolveNamespace(context)

		createImagePullSecret()
		prepareCertManagerApp(repositoryWorkspace.clusterResourcesRepository)
		replaceCertManagerTemplates(repositoryWorkspace.clusterResourcesRepository)
	}

	@Override
	protected void deploy() {
		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				TOOL_NAME,
				namespace,
				config.features.certManager.helm,
				HELM_VALUES_PATH)

		helmToolDeployer.deploy(request,
			context,
			repositoryWorkspace)
	}

	@Override
	protected void publishChanges() {
		publishClusterResourcesChanges(TOOL_NAME)
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}" + "${context.config.features.certManager.namespace}"
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(config,
			namespace)
	}

	private void prepareCertManagerApp(GitRepo clusterResourcesRepo) {
		log.debug('Preparing cert-manager repository content in ' + "${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR,
				CERT_MANAGER_APP_PATH))
	}

	private void replaceCertManagerTemplates(GitRepo clusterResourcesRepo) {
		clusterResourcesRepo.replaceTemplates([config: config])
	}
}