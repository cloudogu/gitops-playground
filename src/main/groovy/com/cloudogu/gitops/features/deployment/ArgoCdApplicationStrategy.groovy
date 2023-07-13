package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.ScmmRepo
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import jakarta.inject.Singleton

import java.nio.file.Path

@Singleton
class ArgoCdApplicationStrategy implements DeploymentStrategy {
    private FileSystemUtils fileSystemUtils
    private Map config
    private CommandExecutor commandExecutor

    ArgoCdApplicationStrategy(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            CommandExecutor commandExecutor
    ) {
        this.fileSystemUtils = fileSystemUtils
        this.commandExecutor = commandExecutor
        this.config = config.getConfig()
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {

        ScmmRepo clusterResourcesRepo = createScmmRepo(config, 'argocd/cluster-resources', commandExecutor)
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
                        namespace: "argocd"
                ],
                spec      : [
                        destination: [
                                server   : "https://kubernetes.default.svc",
                                namespace: namespace
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
                                ]
                        ]
                ],
        ])
        clusterResourcesRepo.writeFile("argocd/${releaseName}.yaml", yamlResult)

        clusterResourcesRepo.commitAndPush("Added $repoName/$chart to ArgoCD")
    }

    protected ScmmRepo createScmmRepo(Map config, String repoTarget, CommandExecutor commandExecutor) {
        return new ScmmRepo(config, repoTarget, commandExecutor)
    }
}
