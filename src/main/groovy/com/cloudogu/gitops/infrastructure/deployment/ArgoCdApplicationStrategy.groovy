package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
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
	private Config config
	private final RepositoryProvisioning repositoryProvisioning

	ArgoCdApplicationStrategy(
		Config config,
		FileSystemUtils fileSystemUtils,
		RepositoryProvisioning repositoryProvisioning
	) {
		this.fileSystemUtils = fileSystemUtils
		this.config = config
		this.repositoryProvisioning = repositoryProvisioning
	}

	@Override
	@SuppressWarnings('GroovyGStringKey')
	// Using dynamic strings as keys seems an easy to read way to avoid more ifs
	void deployFeature(
		String repoURL,
		String repoName,
		String chartOrPath,
		String version,
		String namespace,
		String releaseName,
		Path helmValuesPath,
		RepoType repoType
	) {
		log.trace("Deploying helm chart via ArgoCD: ${releaseName}. Reading values from ${helmValuesPath}")

		RepositoryWorkspace workspace = repositoryProvisioning.provideWorkspace()
		GitRepo clusterResourcesRepo = workspace.clusterResourcesRepository

		def namePrefix = config.application.namePrefix
		def prefix = (namePrefix ?: '').strip()
		def shallCreateNamespace = config.features['argocd']['operator'] ? 'CreateNamespace=false' : 'CreateNamespace=true'

		String project = 'cluster-resources'
		String namespaceName = "${namePrefix}" + config.features.argocd.namespace
		String featureName = repoName
		boolean bootstrapDeploymentRequired = requiresBootstrapDeployment(featureName)

		/*
		 * Important:
		 * featureName remains unprefixed because it is used for paths like apps/scm-manager.
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
		if (config.multiTenant.useDedicatedInstance) {
			namespaceName = "${config.multiTenant.centralArgocdNamespace}"
			project = prefix.replaceFirst(/-$/, '')
		}

		String featurePath = "apps/${featureName}"

		// --- ensure folders exist before writing files ---
		String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
		Path.of(repoRoot, featurePath).toFile().mkdirs()
		Path.of(repoRoot, 'apps/argocd/applications').toFile().mkdirs()

		// 1) GOP-managed values
		String gopValuesPath = "${featurePath}/${featureName}-gop-helm.yaml"
		def inlineValues = helmValuesPath.toFile().text

		// 2) User values
		String userValuesPath = "${featurePath}/${featureName}-user-values.yaml"
		Path userValuesAbsPath = Path.of(repoRoot, userValuesPath)

		if (bootstrapDeploymentRequired) {
			log.info(
				"Using bootstrap deployment for feature '{}': applicationName='{}', releaseName='{}', namespace='{}'. " +
					"Helm values will be embedded into the ArgoCD Application and no external values source will be referenced.",
				featureName,
				repoName,
				releaseName,
				namespace
			)
		} else {
			// Normal features keep values in cluster-resources and consume them via $values.
			clusterResourcesRepo.writeFile(gopValuesPath, inlineValues)

			// User values must NEVER be overwritten by GOP.
			if (!userValuesAbsPath.toFile().exists()) {
				clusterResourcesRepo.writeFile(userValuesPath, '')
			}
		}

		// 1) Helm source
		def helmConfig = [
			releaseName: releaseName
		]

		if (bootstrapDeploymentRequired) {
			log.debug(
				"Embedding Helm values for bootstrap feature '{}' directly into the ArgoCD Application to avoid a self-referencing values source.",
				featureName
			)
			helmConfig.values = inlineValues
		} else {
			helmConfig.valueFiles = [
				"\$values/${gopValuesPath}".toString(),
				"\$values/${userValuesPath}".toString()
			]
			helmConfig.ignoreMissingValueFiles = true
		}

		def helmSource = [
			repoURL                         : repoURL,
			(chooseKeyChartOrPath(repoType)): chartOrPath,
			targetRevision                  : version,
			helm                            : helmConfig
		]

		// 2) Git source for values and additional manifests.
		// SCM-Manager must not reference the SCM-Manager repo that it deploys itself.
		def sources = [helmSource]

		if (!bootstrapDeploymentRequired) {
			/*
			 * Important:
			 * Do not use workspace.clusterResourcesRepositoryUrl() yet.
			 *
			 * GitRepo currently applies config.application.namePrefix internally.
			 * Using clusterResourcesRepository.repoTarget here can therefore lead to
			 * a double prefix like:
			 *
			 *   my-prefix-my-prefix-argocd/cluster-resources
			 *
			 * Until prefixing is moved out of GitRepo, keep the repo target unprefixed here.
			 */
			def featureRepoUrl = "${clusterResourcesRepo.gitProvider.repoPrefix()}argocd/cluster-resources.git".toString()

			def gitSource = [
				repoURL       : featureRepoUrl,
				targetRevision: 'main',
				ref           : 'values',
				path          : featurePath,
				directory     : [recurse: true]
			]

			sources << gitSource
		}

		// Prepare ArgoCD Application YAML
		def yamlMapper = YAMLMapper.builder()
			.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
			.build()

		def yamlResult = yamlMapper.writeValueAsString([
			apiVersion: 'argoproj.io/v1alpha1',
			kind      : 'Application',
			metadata  : [
				name     : repoName,
				namespace: namespaceName
			],
			spec      : [
				destination: [
					server   : 'https://kubernetes.default.svc',
					namespace: namespace
				],
				project    : project,
				sources    : sources,
				syncPolicy : [
					automated  : [
						prune   : true,
						selfHeal: true
					],
					syncOptions: [
						// So that we can apply very large resources, e.g. prometheus CRD.
						'ServerSideApply=true',
						// Create namespaces for helm charts while not using the argocd-operator mode.
						shallCreateNamespace
					]
				]
			]
		])

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

		log.debug(
			"Prepared ArgoCD application for helm release ${releaseName} basing on chart ${chartOrPath} from ${repoURL}, " +
				"version ${version}, into namespace ${namespace}. Application was written to shared repository workspace:\n${yamlResult}"
		)

		repositoryProvisioning.publishClusterResourcesRepositoryChanges(
			featureName,
			"Add ${repoName}/${chartOrPath} to ArgoCD"
		)
	}

	String chooseKeyChartOrPath(RepoType repoType) {
		switch (repoType) {
			case RepoType.HELM:
				return 'chart'
			case RepoType.GIT:
				return 'path'
			default:
				throw new RuntimeException("Repo type ${repoType} not implemented for ${this.class.simpleName}")
		}
	}

	private boolean requiresBootstrapDeployment(String featureName) {
		return featureName == 'scm-manager'
	}
}