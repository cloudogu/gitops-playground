package com.cloudogu.gitops.features.deployment

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify 

import java.nio.file.Files
import java.nio.file.Path

import org.junit.jupiter.api.Test

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.kubernetes.api.HelmClient

class HelmStrategyTest {

    HelmClient helmClient = mock(HelmClient)
    
    @Test
    void 'deploys feature using helm client'() {
        Path valuesYaml = Files.createTempFile('', '')
        
        createStrategy().deployFeature("repoURL", "repoName", "chart", "version", "foo-namespace", "releaseName", valuesYaml)
        
        verify(helmClient).addRepo("repoName", "repoURL")
        verify(helmClient).upgrade("releaseName", "repoName/chart", [
                namespace: "foo-namespace",
                version: "version",
                values: valuesYaml.toString()
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
        new HelmStrategy(new Config([application: [namePrefix: "foo-"]]), helmClient)
    }
}