package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.utils.FileSystemUtils
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
@Slf4j
class ArgoCdApplicationStrategy implements DeploymentStrategy {
    private FileSystemUtils fileSystemUtils
    private Config config
    private final GitRepoFactory gitRepoProvider

    private GitHandler gitHandler

    ArgoCdApplicationStrategy(
            Config config,
            FileSystemUtils fileSystemUtils,
            GitRepoFactory gitRepoProvider,
            GitHandler gitHandler
    ) {
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
        String namespaceName = "${namePrefix}argocd"
        String featureName = repoName

        //DedicatedInstances
        if (config.multiTenant.useDedicatedInstance) {
            repoName = "${config.application.namePrefix}${repoName}"
            namespaceName = "${config.multiTenant.centralArgocdNamespace}"
            project = config.application.namePrefix.replaceFirst(/-$/, "")
        }

        // Feature-Name -> Ordner under apps/<feature>/misc
        String miscPath    = "apps/${featureName}/misc"


        String valuesRelPath = "${miscPath}/${featureName}-gop-helm.yaml"   // relative to repo-root
        // inline values from tmpHelmValues file into ArgoCD Application YAML
        def inlineValues = helmValuesPath.toFile().text
        clusterResourcesRepo.writeFile(valuesRelPath, inlineValues)

        //GOP should not overwrite this file
        String userValuesRelPath = "${miscPath}/${featureName}-user-values.yaml"
        clusterResourcesRepo.writeFile(userValuesRelPath, "")

        // 1) helm source (external chart source)
        def helmSource = [
                repoURL                          : repoURL,
                (chooseKeyChartOrPath(repoType)) : chartOrPath,
                targetRevision                   : version,
                helm                             : [
                        releaseName: releaseName,
                        valueFiles : [
                                "\$values/${valuesRelPath}".toString(),
                                "\$values/${userValuesRelPath}".toString()
                        ],
                        ignoreMissingValueFiles: true
                ]
        ]

        // 2) Git source for misc + values
        //   - repoURL: cluster-resources repo
        //   - ref: values → used in valueFiles as $values
        //   - path: apps/<feature>/misc → additional manifests
        def miscRepoUrl = "${clusterResourcesRepo.gitProvider.repoPrefix()}argocd/cluster-resources.git".toString()
        def miscSource = [
                repoURL       :  miscRepoUrl,
                targetRevision: "main",
                ref           : "values",
                path          : miscPath,
                directory     : [recurse: true]
        ]

        def sources = [helmSource, miscSource]

        // Prepare ArgoCD Application YAML
        def yamlMapper = YAMLMapper.builder()
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                .build()

        def yamlResult = yamlMapper.writeValueAsString([
                apiVersion: "argoproj.io/v1alpha1",
                kind      : "Application",
                metadata  : [
                        name     : repoName,
                        namespace: namespaceName
                ],
                spec      : [
                        destination: [
                                server   : "https://kubernetes.default.svc",
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
                                        // So that we can apply very large resources (e.g. prometheus CRD)
                                        "ServerSideApply=true",
                                        // Create namespaces for helm charts (while not using the argocd-operater mode)
                                        shallCreateNamespace
                                ]
                        ]
                ]
        ])

        String appManifestPath="apps/argocd/applications/${releaseName}.yaml"
        clusterResourcesRepo.writeFile(appManifestPath, yamlResult)

        log.debug("Deploying helm release ${releaseName} basing on chart ${chartOrPath} from ${repoURL}, version " +
                "${version}, into namespace ${namespace}. Using Argo CD application:\n${yamlResult}")

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