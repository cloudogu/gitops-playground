package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
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
    private final ScmmRepoProvider scmmRepoProvider

    ArgoCdApplicationStrategy(
            Config config,
            FileSystemUtils fileSystemUtils,
            ScmmRepoProvider scmmRepoProvider
    ) {
        this.scmmRepoProvider = scmmRepoProvider
        this.fileSystemUtils = fileSystemUtils
        this.config = config
    }

    @Override
    @SuppressWarnings('GroovyGStringKey')
    // Using dynamic strings as keys seems an easy to read way to avoid more ifs
    void deployFeature(String repoURL, String repoName, String chartOrPath, String version, String namespace,
                       String releaseName, Path helmValuesPath, RepoType repoType) {
        log.trace("Deploying helm chart via ArgoCD: ${releaseName}. Reading values from ${helmValuesPath}")
        def namePrefix = config.application.namePrefix
        def shallCreateNamespace = config.features['argocd']['operator'] ? "CreateNamespace=false" : "CreateNamespace=true"

        ScmmRepo clusterResourcesRepo = scmmRepoProvider.getRepo('argocd/cluster-resources', config.multiTenant.useDedicatedInstance)
        clusterResourcesRepo.cloneRepo()

        // Inline values from tmpHelmValues file into ArgoCD Application YAML
        def inlineValues = helmValuesPath.toFile().text

        String project = "cluster-resources"
        String namespaceName = "${namePrefix}argocd"

        //DedicatedInstances
        if (config.multiTenant.useDedicatedInstance) {
            repoName = "${config.application.namePrefix}${repoName}"
            namespaceName = "argocd"
            project = ${config.application.namePrefix}replaceFirst(/-$/, "")
        }

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
                        sources    : [[
                                              repoURL                         : repoURL,
                                              "${chooseKeyChartOrPath(repoType)}": chartOrPath,
                                              targetRevision                  : version,
                                              helm                            : [
                                                      releaseName: releaseName,
                                                      values     : inlineValues
                                              ]
                                      ]],
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

        clusterResourcesRepo.writeFile("argocd/${releaseName}.yaml", yamlResult)

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