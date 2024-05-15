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
    @SuppressWarnings('GroovyGStringKey') // Using dynamic strings as keys seems an easy to read way to avoid more ifs
    void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
                       String releaseName, Path helmValuesPath, RepoType repoType) {
        def namePrefix = config.application['namePrefix']

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
                                        repoURL                            : repoURL,
                                        "${chooseKeyChartOrPath(repoType)}": chartOrPath,
                                        targetRevision                     : version,
                                        helm                               : [
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
                                        // Create namespaces for helm charts
                                        "CreateNamespace=true"
                                ]
                        ]
                ],
        ])
        clusterResourcesRepo.writeFile("argocd/${releaseName}.yaml", yamlResult)

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
