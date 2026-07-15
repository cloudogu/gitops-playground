package com.cloudogu.gitops.infrastructure.deployment.argocd

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy
import com.cloudogu.gitops.infrastructure.git.GitRepo

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper

@CompileStatic
@Singleton
@Slf4j
class ArgoCdApplicationStrategy implements DeploymentStrategy {

	private final ArgoCdApplicationTargetResolver targetResolver

	ArgoCdApplicationStrategy(ArgoCdApplicationTargetResolver targetResolver) {
		this.targetResolver = targetResolver
	}

	@Override
	@SuppressWarnings('GroovyGStringKey')
	void deployFeature(String repoURL,
		String repoName,
		String chartOrPath,
		String version,
		String namespace,
		String releaseName,
		Path helmValuesPath,
		RepoType repoType,
		DeploymentContext context,
		RepositoryWorkspace repositoryWorkspace) {
		log.trace("Deploying helm chart via ArgoCD: ${releaseName}. Reading values from ${helmValuesPath}")

		GitRepo clusterResourcesRepo = repositoryWorkspace.clusterResourcesRepository

		String toolName = repoName
		boolean bootstrapDeploymentRequired = requiresBootstrapDeployment(toolName)
		ArgoCdApplicationTarget target = targetResolver.resolve(context, repoName)

		String toolPath = "apps/${toolName}"

		String repoRoot = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
		Path.of(repoRoot, toolPath).toFile().mkdirs()
		Path.of(repoRoot, 'apps/argocd/applications').toFile().mkdirs()

		String gopValuesPath = "${toolPath}/${toolName}-gop-helm.yaml"
		String inlineValues = helmValuesPath.toFile().text

		String userValuesPath = "${toolPath}/${toolName}-user-values.yaml"
		Path userValuesAbsPath = Path.of(repoRoot, userValuesPath)

		if (bootstrapDeploymentRequired) {
			log.info('Using bootstrap deployment for tool \'{}\': applicationName=\'{}\', releaseName=\'{}\', namespace=\'{}\'. ' +
				'Helm values will be embedded into the ArgoCD Application and no external values source will be referenced.',
				toolName,
				target.applicationName,
				releaseName,
				namespace)
		} else {
			clusterResourcesRepo.writeFile(gopValuesPath, inlineValues)

			if (!userValuesAbsPath.toFile().exists()) {
				clusterResourcesRepo.writeFile(userValuesPath, '')
			}
		}

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

		String namespaceCreationSyncOption = "CreateNamespace=${target.createDestinationNamespace}".toString()

		def yamlMapper = YAMLMapper.builder()
			.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
			.build()

		def yamlResult = yamlMapper.writeValueAsString([apiVersion: 'argoproj.io/v1alpha1',
		                                                kind      : 'Application',
		                                                metadata  : [name     : target.applicationName,
		                                                             namespace: target.namespace],
		                                                spec      : [destination: [server   : 'https://kubernetes.default.svc',
		                                                                           namespace: namespace],
		                                                             project    : target.project,
		                                                             sources    : sources,
		                                                             syncPolicy : [automated  : [prune   : true,
		                                                                                         selfHeal: true],
		                                                                           syncOptions: ['ServerSideApply=true',
		                                                                                         namespaceCreationSyncOption]]]])

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

		log.debug("Prepared ArgoCD application for helm release ${releaseName} basing on chart ${chartOrPath} from ${repoURL}, " + "version ${version}, into namespace ${namespace}. Application was written to shared repository workspace:\n${yamlResult}")
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

	private boolean requiresBootstrapDeployment(String toolName) {
		return toolName == 'scm-manager'
	}
}