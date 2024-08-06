package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TestScmmRepoProvider
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ArgoCdApplicationStrategyTest {
    private File localTempDir

    @Test
    void 'deploys feature using argo CD'() {
        def strategy = createStrategy()
        File valuesYaml = File.createTempFile('values', 'yaml')
        valuesYaml.text = """
param1: value1
param2: value2
"""
        strategy.deployFeature("repoURL", "repoName", "chartName", "version", 
                "namespace", "releaseName", valuesYaml.toPath())

        def argoCdApplicationYaml = new File("$localTempDir/argocd/releaseName.yaml")
        assertThat(argoCdApplicationYaml.text).isEqualTo("""---
apiVersion: "argoproj.io/v1alpha1"
kind: "Application"
metadata:
  name: "repoName"
  namespace: "foo-argocd"
spec:
  destination:
    server: "https://kubernetes.default.svc"
    namespace: "foo-namespace"
  project: "cluster-resources"
  sources:
  - repoURL: "repoURL"
    chart: "chartName"
    targetRevision: "version"
    helm:
      releaseName: "releaseName"
      values: |2

        param1: value1
        param2: value2
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
    - "ServerSideApply=true"
    - "CreateNamespace=true"
""")
    }
    
    @Test
    void 'deploys feature using argo CD from git repo'() {
        def strategy = createStrategy()
        File valuesYaml = File.createTempFile('values', 'yaml')
        strategy.deployFeature("repoURL", "repoName", "chartName", "version", 
                "namespace", "releaseName", valuesYaml.toPath(), DeploymentStrategy.RepoType.GIT)

        def argoCdApplicationYaml = new File("$localTempDir/argocd/releaseName.yaml")
        def result = new YamlSlurper().parse(argoCdApplicationYaml)
        def sources = result['spec']['sources'] as List
        assertThat(sources[0] as Map).containsKey('path')
        assertThat(sources[0] ['path']).isEqualTo('chartName')
    }

    private ArgoCdApplicationStrategy createStrategy() {
        Map config = [
                scmm: [
                        internal: false,
                        username: "dont-care-username",
                        password: "dont-care-password",
                        protocol: "https",
                        host: "localhost"
                ],
                application: [
                        namePrefix : 'foo-',
                        gitName    : 'Cloudogu',
                        gitEmail   : 'hello@cloudogu.com'
                ]
        ]


        def repoProvider = new TestScmmRepoProvider(new Configuration(config), new FileSystemUtils()) {
            @Override
            ScmmRepo getRepo(String repoTarget) {
                def repo = super.getRepo(repoTarget)
                localTempDir = new File(repo.getAbsoluteLocalRepoTmpDir())

                return repo
            }
        }

        return new ArgoCdApplicationStrategy(new Configuration(config), new FileSystemUtils(), repoProvider)
    }
}
