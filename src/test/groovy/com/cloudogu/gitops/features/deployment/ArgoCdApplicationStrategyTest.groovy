package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ArgoCdApplicationStrategyTest {
    private File localTempDir

    @Test
    void 'deploys feature using argo CD'() {
        def commandExecutor = new CommandExecutorForTest()
        def strategy = createStrategy(commandExecutor)
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
  namespace: "argocd"
spec:
  destination:
    server: "https://kubernetes.default.svc"
    namespace: "namespace"
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
""")
        assertThat(commandExecutor.actualCommands[0]).startsWith("git clone ")
    }

    private ArgoCdApplicationStrategy createStrategy(CommandExecutor executor) {
        Map config = [
                scmm: [
                        internal: false,
                        username: "dont-care-username",
                        password: "dont-care-password",
                        protocol: "https",
                        host: "localhost"
                ]
        ]


        def repoProvider = new ScmmRepoProvider(new Configuration(config), executor, new FileSystemUtils()) {
            @Override
            ScmmRepo getRepo(String repoTarget) {
                def repo = super.getRepo(repoTarget)
                localTempDir = new File(repo.getAbsoluteLocalRepoTmpDir())

                return repo
            }
        }

        return new ArgoCdApplicationStrategy(new Configuration(config), new FileSystemUtils(), executor, repoProvider)
    }
}
