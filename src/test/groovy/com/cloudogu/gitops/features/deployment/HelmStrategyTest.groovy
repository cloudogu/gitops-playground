package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.utils.HelmClient
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify

class HelmStrategyTest {
    @Test
    void 'deploys feature using helm client'() {
        def helmClient = mock(HelmClient)
        def strategy = new HelmStrategy([application: [namePrefix: "foo-"]], helmClient)
        strategy.deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"))
        verify(helmClient).addRepo("repoName", "repoURL")
        verify(helmClient).upgrade("releaseName", "repoName/chart", [
                namespace: "foo-namespace",
                version: "version",
                values: "values.yaml"
        ])
    }
}
