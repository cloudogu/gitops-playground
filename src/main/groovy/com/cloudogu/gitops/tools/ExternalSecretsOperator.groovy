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
@Order(400)
class ExternalSecretsOperator extends Tool {

	static final String HELM_VALUES_PATH =
		'argocd/cluster-resources/apps/external-secrets/templates/values.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR =
		'argocd/cluster-resources'
	private static final String TOOL_NAME = 'external-secrets'
	private static final String RELEASE_NAME = 'external-secrets'
	private static final String EXTERNAL_SECRETS_APP_PATH =
		'apps/external-secrets'

	private final HelmToolDeployer helmToolDeployer
	private final ImagePullSecretCreator imagePullSecretCreator

	String namespace

	ExternalSecretsOperator(HelmToolDeployer helmToolDeployer,
		ImagePullSecretCreator imagePullSecretCreator) {
		this.helmToolDeployer = helmToolDeployer
		this.imagePullSecretCreator = imagePullSecretCreator
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.secrets.active
	}

	@Override
	protected void preDeploy() {
		this.namespace = resolveNamespace(context)

		createImagePullSecret()
		prepareExternalSecretsApp(repositoryWorkspace.clusterResourcesRepository)
	}

	@Override
	protected void deploy() {
		HelmToolDeploymentRequest request =
			new HelmToolDeploymentRequest(TOOL_NAME,
				RELEASE_NAME,
				namespace,
				config.features.secrets.externalSecrets.helm,
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
		return "${context.config.application.namePrefix}" + "${context.config.features.secrets.namespace}"
	}

	private void createImagePullSecret() {
		imagePullSecretCreator.createIfRequired(config,
			namespace)
	}

	private void prepareExternalSecretsApp(GitRepo clusterResourcesRepo) {
		log.debug('Preparing external-secrets repository content in ' + "${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR,
				EXTERNAL_SECRETS_APP_PATH))
	}
}