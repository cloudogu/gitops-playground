package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
class ArgoCdApplicationStrategy implements DeploymentStrategy {
    private FileSystemUtils fileSystemUtils
    private Map config
    private final ScmmRepoProvider scmmRepoProvider

    ArgoCdApplicationStrategy(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            ScmmRepoProvider scmmRepoProvider
    ) {
        this.scmmRepoProvider = scmmRepoProvider
        this.fileSystemUtils = fileSystemUtils
        this.config = config.getConfig()
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {
        def namePrefix = config.application['namePrefix']
        def shallCreateNamespace  = config.features['argocd']['operator'] ? "CreateNamespace=false" : "CreateNamespace=true"

        ScmmRepo clusterResourcesRepo = scmmRepoProvider.getRepo('argocd/cluster-resources')
        clusterResourcesRepo.cloneRepo()

        // Inline values from tmpHelmValues file into ArgoCD Application YAML
        def inlineValues = helmValuesPath.text

        // Write chart, repoURL and version into a ArgoCD Application YAML

        def yamlMapper = YAMLMapper.builder().enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE).build()
        def yamlResult = yamlMapper.writeValueAsString([
                apiVersion: "argoproj.io/v1alpha1",
                kind      : "Application",
                metadata  : [
                        name     : repoName,
                        namespace: "${namePrefix}argocd".toString()
                ],
                spec      : [
                        destination: [
                                server   : "https://kubernetes.default.svc",
                                namespace: "${namePrefix}${namespace}".toString()
                        ],
                        project    : "cluster-resources",
                        sources    : [
                                [
                                        repoURL       : repoURL,
                                        chart         : chart,
                                        targetRevision: version,
                                        helm          : [
                                                releaseName: releaseName,
                                                values: inlineValues
                                        ],
                                ],
                        ],
                        syncPolicy : [
                                automated: [
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
                ],
        ])
        clusterResourcesRepo.writeFile("argocd/${releaseName}.yaml", yamlResult)

        clusterResourcesRepo.commitAndPush("Added $repoName/$chart to ArgoCD")
    }
}
