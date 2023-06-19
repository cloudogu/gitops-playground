package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmValuesConverter
import com.cloudogu.gitops.utils.ScmmRepo
import groovy.yaml.YamlBuilder

import java.nio.file.Path

class ArgoCdApplicationStrategy implements DeploymentStrategy {
    private FileSystemUtils fileSystemUtils
    private Map config
    private CommandExecutor commandExecutor

    ArgoCdApplicationStrategy(
            Map config,
            FileSystemUtils fileSystemUtils,
            CommandExecutor commandExecutor = new CommandExecutor()
    ) {
        this.fileSystemUtils = fileSystemUtils
        this.commandExecutor = commandExecutor
        this.config = config
    }

    @Override
    void deployFeature(String repoURL, String repoName, String chart, String version, String namespace, String releaseName, Path helmValuesPath) {

        ScmmRepo clusterResourcesRepo = createScmmRepo(config, 'argocd/cluster-resources', commandExecutor)
        clusterResourcesRepo.cloneRepo()

        // Inline values from tmpHelmValues file into ArgoCD Application YAML
        def parameters = (new HelmValuesConverter()).flattenValues(fileSystemUtils.readYaml(helmValuesPath))

        // Write chart, repoURL and version into a ArgoCD Application YAML
        def yamlBuilder = new YamlBuilder()
        yamlBuilder([
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
                                                parameters: parameters
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
        clusterResourcesRepo.writeFile("argocd/${releaseName}.yaml", yamlBuilder.toString())

        clusterResourcesRepo.commitAndPush("Added $repoName/$chart to ArgoCD")
    }

    protected ScmmRepo createScmmRepo(Map config, String repoTarget, CommandExecutor commandExecutor) {
        return new ScmmRepo(config, repoTarget, commandExecutor)
    }
}
