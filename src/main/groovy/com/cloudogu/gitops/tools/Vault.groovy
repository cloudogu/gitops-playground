package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.ImagePullSecretCreator
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.ClusterResourcesCopyFilter
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Slf4j
@Singleton
@Order(500)
class Vault extends Tool {

	static final String VAULT_START_SCRIPT_PATH = 'argocd/cluster-resources/apps/vault/templates/dev-post-start.ftl.sh'
	static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/vault/templates/values.ftl.yaml'

	private static final String CLUSTER_RESOURCES_SOURCE_DIR = 'argocd/cluster-resources'
	private static final String TOOL_NAME = 'vault'
	private static final String RELEASE_NAME = 'vault'
	private static final String VAULT_APP_PATH = 'apps/vault'

	private final ImagePullSecretCreator imagePullSecretCreator

	String namespace
	final K8sClient k8sClient

	Vault(FileSystemUtils fileSystemUtils,
		Deployer deployer,
		K8sClient k8sClient,
		AirGappedUtils airGappedUtils,
		GitHandler gitHandler,
		ImagePullSecretCreator imagePullSecretCreator) {
		this.deployer = deployer
		this.fileSystemUtils = fileSystemUtils
		this.k8sClient = k8sClient
		this.airGappedUtils = airGappedUtils
		this.gitHandler = gitHandler
		this.imagePullSecretCreator = imagePullSecretCreator
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
		imagePullSecretCreator.createIfRequired(config, namespace)

		prepareVaultApp(repositoryWorkspace.clusterResourcesRepository)

		// Note that some specific configuration steps are implemented in ArgoCD
		def helmConfig = config.features.secrets.vault.helm

		addHelmValuesData('host', config.features.secrets.vault.url ? new URL(config.features.secrets.vault.url as String).host : '')

		String vaultMode = config.features.secrets.vault.mode
		if (vaultMode == 'dev') {
			log.debug('WARNING! Vault dev mode is enabled! In this mode, Vault runs entirely in-memory\n' + 'and starts unsealed with a single unseal key. ')

			// Create config map from init script
			// Init script creates/authorizes secrets, users, service accounts, etc.
			def vaultPostStartConfigMap = 'vault-dev-post-start'
			def vaultPostStartVolume = 'dev-post-start'

			def templatedFile = fileSystemUtils.copyToTempDir(fileSystemUtils.getRootDir() + '/' + VAULT_START_SCRIPT_PATH)
			def postStartScript = new TemplatingEngine().replaceTemplate(templatedFile.toFile(), [namePrefix: config.application.namePrefix])

			log.debug('Creating namespace for vault, so it can add its secrets there')
			k8sClient.createNamespace(namespace)
			k8sClient.createConfigMapFromFile(vaultPostStartConfigMap, namespace, postStartScript.absolutePath)

			addHelmValuesData('dev',
				[rootToken              : UUID.randomUUID(),
				 vaultPostStartConfigMap: vaultPostStartConfigMap,
				 vaultPostStartVolume   : vaultPostStartVolume,
				 postStartScriptName    : postStartScript.name])
		}

		deployHelmChart(TOOL_NAME,
			RELEASE_NAME,
			namespace,
			helmConfig,
			HELM_VALUES_PATH,
			context)

		repositoryWorkspace.commitAndPushClusterResourcesChanges("Update ${TOOL_NAME} GitOps resources")
	}

	private void prepareVaultApp(GitRepo clusterResourcesRepo) {
		log.debug("Preparing vault repository content in ${clusterResourcesRepo.repoTarget}")

		clusterResourcesRepo.copyDirectoryContents(CLUSTER_RESOURCES_SOURCE_DIR,
			ClusterResourcesCopyFilter.forSubDir(CLUSTER_RESOURCES_SOURCE_DIR, VAULT_APP_PATH))
	}
}