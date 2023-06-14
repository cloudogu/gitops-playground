package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
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
        // Helm "dependencies" via Git seems not be supported -> For Umbrella Charts: No support for Charts from git
        // https://github.com/helm/helm/issues/9461
        // There is a plugin, but using helm plugins in Argo CD might be impossible, will increase complexity at any rate
        // https://github.com/aslafy-z/helm-git
        // Unclear if argoCD supports credentials for umbrella charts
        // https://github.com/argoproj/argo-cd/issues/7104#issuecomment-995366406
        // Multi Source applications would be a better solution, but as of latest argo CD 2.7 they're still in beta
        // -> We use argo cd applications with the values.yaml inlined

        ScmmRepo clusterResourcesRepo = createScmmRepo(config, 'argocd/cluster-resources', commandExecutor)
        clusterResourcesRepo.cloneRepo()
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
                                                valueFiles : [
                                                        '$cluster-resources-git/apps/mailhog/values.yaml'
                                                ],
                                        ],
                                ],
                                [
                                        repoURL       : "http://scmm-scm-manager.default.svc.cluster.local/scm/repo/argocd/cluster-resources",
                                        targetRevision: "main",
                                        ref           : "cluster-resources-git"
                                ]
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

        // Inline values from tmpHelmValues file into ArgoCD Application YAML
        clusterResourcesRepo.writeFile("apps/mailhog/values.yaml", helmValuesPath.text)

        // push to cluster-resources repo
        clusterResourcesRepo.commitAndPush("Added $repoName/$chart to ArgoCD")
    }

    protected ScmmRepo createScmmRepo(Map config, String repoTarget, CommandExecutor commandExecutor) {
        return new ScmmRepo(config, repoTarget, commandExecutor)
    }
}
