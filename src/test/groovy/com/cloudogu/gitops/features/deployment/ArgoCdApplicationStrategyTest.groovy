package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.ScmmRepo
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ArgoCdApplicationStrategyTest {
    @Test
    void 'deploys feature using argo CD'() {
        def commandExecutor = new CommandExecutorForTest()
        File localTempDir = File.createTempDir()
        def strategy = createStrategy(commandExecutor, localTempDir)
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
      parameters:
      - name: "param1"
        value: "value1"
      - name: "param2"
        value: "value2"
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
""")
        assertThat(commandExecutor.actualCommands[0]).startsWith("git clone ")
    }

    private ArgoCdApplicationStrategy createStrategy(CommandExecutor executor, File localTempDir) {
        Map config = [
                scmm: [
                        internal: false,
                        username: "dont-care-username",
                        password: "dont-care-password",
                        protocol: "https",
                        host: "localhost"
                ]
        ]

        return new ArgoCdApplicationStrategy(config, new FileSystemUtils(), executor) {
            @Override
            protected ScmmRepo createScmmRepo(Map repoConfig, String repoTarget, CommandExecutor commandExecutor) {
                return new ScmmRepo(repoConfig, repoTarget, localTempDir.absolutePath, commandExecutor)
            }
        }
    }
}
