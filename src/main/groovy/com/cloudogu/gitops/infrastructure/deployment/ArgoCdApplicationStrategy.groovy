package com.cloudogu.gitops.infrastructure.deployment

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
	private Config config
	private final GitRepoFactory gitRepoProvider

	private GitHandler gitHandler

	ArgoCdApplicationStrategy(Config config,
		FileSystemUtils fileSystemUtils,
		GitRepoFactory gitRepoProvider,
		GitHandler gitHandler) {
		this.gitRepoProvider = gitRepoProvider
		this.fileSystemUtils = fileSystemUtils
		this.config = config
		this.gitHandler = gitHandler
	}

	@Override
	@SuppressWarnings('GroovyGStringKey')
	// Using dynamic strings as keys seems an easy to read way to avoid more ifs
	void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
		String releaseName, Path helmValuesPath, RepoType repoType) {
		log.trace("Deploying helm chart via ArgoCD: ${releaseName}. Reading values from ${helmValuesPath}")

		def namePrefix = config.application.namePrefix
		def shallCreateNamespace = config.features['argocd']['operator'] ? "CreateNamespace=false" : "CreateNamespace=true"

		GitRepo clusterResourcesRepo = gitRepoProvider.getRepo('argocd/cluster-resources', this.gitHandler.resourcesScm)
		clusterResourcesRepo.cloneRepo()

		String project = "cluster-resources"
		String namespaceName = "${namePrefix}" + config.features.argocd.namespace
		String featureName = repoName
		boolean isScmManager = featureName == 'scm-manager'

		// DedicatedInstances
		if (config.multiTenant.useDedicatedInstance) {
			repoName = "${config.application.namePrefix}${repoName}"
			namespaceName = "${config.multiTenant.centralArgocdNamespace}"
			project = config.application.namePrefix.replaceFirst(/-$/, "")
		}

		// Feature-Name -> Ordner under apps/<feature>
		String featurePath = "apps/${featureName}"

		// --- ensure folders exist before writing files ---
		String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
		Path.of(repoRoot, featurePath).toFile().mkdirs()

		// 1) GOP-managed values
		String gopValuesPath = "${featurePath}/${featureName}-gop-helm.yaml"
		def inlineValues = helmValuesPath.toFile().text

		// 2) User values
		String userValuesPath = "${featurePath}/${featureName}-user-values.yaml"
		Path userValuesAbsPath = Path.of(repoRoot, userValuesPath)

		if (isScmManager) {
			log.info(
				"Deploying SCM-Manager as bootstrap component: using inline Helm values and omitting self-referencing values source. releaseName='{}', namespace='{}'",
				releaseName,
				namespace
			)
		} else {
			// Normal tools keep values in cluster-resources and consume them via $values.
			clusterResourcesRepo.writeFile(gopValuesPath, inlineValues)

			// User values must NEVER be overwritten by GOP.
			if (!userValuesAbsPath.toFile().exists()) {
				clusterResourcesRepo.writeFile(userValuesPath, "")
			}
		}

		// 1) Helm source
		def helmConfig = [
			releaseName: releaseName
		]

		if (isScmManager) {
			// SCM-Manager is a bootstrap component. It must not reference values from the SCM it deploys itself.
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

		if (!isScmManager) {
			def featureRepoUrl = "${clusterResourcesRepo.gitProvider.repoPrefix()}argocd/cluster-resources.git".toString()
			def gitSource = [
				repoURL       : featureRepoUrl,
				targetRevision: "main",
				ref           : "values",
				path          : featurePath,
				directory     : [recurse: true]
			]

			sources << gitSource
		}

		// Prepare ArgoCD Application YAML
		def yamlMapper = YAMLMapper.builder()
			.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
			.build()

		def yamlResult = yamlMapper.writeValueAsString([apiVersion: "argoproj.io/v1alpha1",
		                                                kind      : "Application",
		                                                metadata  : [name     : repoName,
		                                                             namespace: namespaceName],
		                                                spec      : [destination: [server   : "https://kubernetes.default.svc",
		                                                                           namespace: namespace],
		                                                             project    : project,
		                                                             sources    : sources,
		                                                             syncPolicy : [automated  : [prune   : true,
		                                                                                         selfHeal: true],
		                                                                           syncOptions: [// So that we can apply very large resources (e.g. prometheus CRD)
		                                                                                         "ServerSideApply=true",
		                                                                                         // Create namespaces for helm charts (while not using the argocd-operater mode)
		                                                                                         shallCreateNamespace]]]])

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
}
