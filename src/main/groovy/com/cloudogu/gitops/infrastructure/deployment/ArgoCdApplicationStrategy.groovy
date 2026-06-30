package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.utils.FileSystemUtils

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper

@Singleton
@Slf4j
class ArgoCdApplicationStrategy implements DeploymentStrategy {
	private FileSystemUtils fileSystemUtils
	private DeploymentContext context
	private final GitRepoFactory gitRepoProvider

	private GitHandler gitHandler

	ArgoCdApplicationStrategy(DeploymentContext context,
		FileSystemUtils fileSystemUtils,
		GitRepoFactory gitRepoProvider,
		GitHandler gitHandler) {
		this.gitRepoProvider = gitRepoProvider
		this.fileSystemUtils = fileSystemUtils
		this.context = context
		this.gitHandler = gitHandler
	}

	private Config getConfig() {
		return context.config
	}

	@Override
	@SuppressWarnings('GroovyGStringKey')
	// Using dynamic strings as keys seems an easy to read way to avoid more ifs
	void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
		String releaseName, Path helmValuesPath, RepoType repoType) {
		log.trace("Deploying helm chart via ArgoCD: ${releaseName}. Reading values from ${helmValuesPath}")

		def namePrefix = config.application.namePrefix
		def prefix = (namePrefix ?: '').strip()
		def shallCreateNamespace = config.features['argocd']['operator'] ? 'CreateNamespace=false' : 'CreateNamespace=true'

		GitRepo clusterResourcesRepo = gitRepoProvider.getRepo('argocd/cluster-resources', this.gitHandler.resourcesScm)
		clusterResourcesRepo.cloneRepo()

		String project = 'cluster-resources'
		String namespaceName = "${namePrefix}" + config.features.argocd.namespace
		String toolName = repoName
		boolean bootstrapDeploymentRequired = requiresBootstrapDeployment(toolName)

		/*
		 * Important:
		 * toolName remains unprefixed because it is used for paths like apps/scm-manager.
		 * repoName becomes the ArgoCD Application metadata.name.
		 *
		 * This avoids ArgoCD tracking-id collisions:
		 * central:
		 *   metadata.name: scm-manager
		 * tenant:
		 *   metadata.name: tenant1-scm-manager
		 * Without this, both central and tenant resources can get tracking IDs starting with: scm-manager:/...
		 */
		if (prefix) {
			repoName = "${prefix}${repoName}"
		}

		// DedicatedInstances
		if (context.isMultiTenant()) {
			namespaceName = "${config.multiTenant.centralArgocdNamespace}"
			project = prefix.replaceFirst(/-$/, '')
		}

		String toolPath = "apps/${toolName}"

		// --- ensure folders exist before writing files ---
		String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
		Path.of(repoRoot, toolPath).toFile().mkdirs()

		// 1) GOP-managed values
		String gopValuesPath = "${toolPath}/${toolName}-gop-helm.yaml"
		def inlineValues = helmValuesPath.toFile().text

		// 2) User values
		String userValuesPath = "${toolPath}/${toolName}-user-values.yaml"
		Path userValuesAbsPath = Path.of(repoRoot, userValuesPath)

		if (bootstrapDeploymentRequired) {
			log.info('Using bootstrap deployment for tool \'{}\': applicationName=\'{}\', releaseName=\'{}\', namespace=\'{}\'. ' +
				'Helm values will be embedded into the ArgoCD Application and no external values source will be referenced.',
				toolName,
				repoName,
				releaseName,
				namespace)
		} else {
			// Normal tools keep values in cluster-resources and consume them via $values.
			clusterResourcesRepo.writeFile(gopValuesPath, inlineValues)

			// User values must NEVER be overwritten by GOP.
			if (!userValuesAbsPath.toFile().exists()) {
				clusterResourcesRepo.writeFile(userValuesPath, '')
			}
		}

		// 1) Helm source
		def helmConfig = [releaseName: releaseName]

		if (bootstrapDeploymentRequired) {
			log.trace("Embedding Helm values for bootstrap tool '{}' directly into the ArgoCD Application to avoid a self-referencing values source.",
				toolName)
			helmConfig.values = inlineValues
		} else {
			helmConfig.valueFiles = ["\$values/${gopValuesPath}".toString(),
			                         "\$values/${userValuesPath}".toString()]
			helmConfig.ignoreMissingValueFiles = true
		}

		def helmSource = [repoURL                         : repoURL,
		                  (chooseKeyChartOrPath(repoType)): chartOrPath,
		                  targetRevision                  : version,
		                  helm                            : helmConfig]

		// 2) Git source for values and additional manifests.
		// SCM-Manager must not reference the SCM-Manager repo that it deploys itself.
		def sources = [helmSource]

		if (!bootstrapDeploymentRequired) {
			def toolRepoUrl = "${clusterResourcesRepo.gitProvider.repoPrefix()}argocd/cluster-resources.git".toString()
			def gitSource = [repoURL       : toolRepoUrl,
			                 targetRevision: 'main',
			                 ref           : 'values',
			                 path          : toolPath,
			                 directory     : [recurse: true]]

			sources << gitSource
		}

		// Prepare ArgoCD Application YAML
		def yamlMapper = YAMLMapper.builder()
			.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
			.build()

		def yamlResult = yamlMapper.writeValueAsString([apiVersion: 'argoproj.io/v1alpha1',
		                                                kind      : 'Application',
		                                                metadata  : [name     : repoName,
		                                                             namespace: namespaceName],
		                                                spec      : [destination: [server   : 'https://kubernetes.default.svc',
		                                                                           namespace: namespace],
		                                                             project    : project,
		                                                             sources    : sources,
		                                                             syncPolicy : [automated  : [prune   : true,
		                                                                                         selfHeal: true],
		                                                                           syncOptions: [// So that we can apply very large resources (e.g. prometheus CRD)
		                                                                                         'ServerSideApply=true',
		                                                                                         // Create namespaces for helm charts (while not using the argocd-operater mode)
		                                                                                         shallCreateNamespace]]]])

		/*
		 * Keep the file path release-based.
		 *
		 * For tenant SCM this becomes:
		 *   apps/argocd/applications/tenant1-scmm.yaml
		 *
		 * The important value for ArgoCD tracking is metadata.name above:
		 *   tenant1-scm-manager
		 */
		String appManifestPath = "apps/argocd/applications/${releaseName}.yaml"

		clusterResourcesRepo.writeFile(appManifestPath, yamlResult)

		log.debug("Deploying helm release ${releaseName} basing on chart ${chartOrPath} from ${repoURL}, version " + "${version}, into namespace ${namespace}. Using Argo CD application:\n${yamlResult}")

		clusterResourcesRepo.commitAndPush("Added $repoName/$chartOrPath to ArgoCD")
	}

	String chooseKeyChartOrPath(RepoType repoType) {
		switch (repoType) {
			case RepoType.HELM: 'chart'
				break
			case RepoType.GIT: 'path'
				break
			default: throw new RuntimeException("Repo type ${repoType} not implemented for ${this.class.simpleName}")
		}
	}

	private boolean requiresBootstrapDeployment(String toolName) {
		return toolName == 'scm-manager'
	}
}
