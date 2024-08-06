package com.cloudogu.gitops.features.deployment

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.HelmClient
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify 

class HelmStrategyTest {

    HelmClient helmClient = mock(HelmClient)
    
    @Test
    void 'deploys feature using helm client'() {
        createStrategy().deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"))
        
        verify(helmClient).addRepo("repoName", "repoURL")
        verify(helmClient).upgrade("releaseName", "repoName/chart", [
                namespace: "foo-namespace",
                version: "version",
                values: "values.yaml"
        ])
    }

    @Test
    void 'Fails to deploy from git'() {
        def exception = shouldFail(RuntimeException) {
            createStrategy().deployFeature("http://repoURL", "repoName", "chart", "version", "namespace",
                    "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.GIT)
        }
        assertThat(exception.message).isEqualTo(
                "Unable to deploy helm chart via Helm CLI from Git URL, because helm does not support this out of the box.\n" +
                "Repo URL: http://repoURL")
        
    }
    
    protected HelmStrategy createStrategy() {
        new HelmStrategy(new Configuration([application: [namePrefix: "foo-"]]), helmClient)
    }
}
