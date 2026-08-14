package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.helm.HelmClient
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify

class HelmStrategyTest {

    HelmClient helmClient = mock(HelmClient)

    @Test
    void 'deploys feature using helm client'() {
        Path valuesYaml = Files.createTempFile('', '')
        DeploymentContext context = new ContextBuilder(createConfig()).build()

        createStrategy().deployFeature('repoURL',
                'repoName',
                'chart',
                'version',
                'foo-namespace',
                'releaseName',
                valuesYaml,
                DeploymentStrategy.RepoType.HELM,
                context,
                null as RepositoryWorkspace)

        verify(helmClient).addRepo('repoName', 'repoURL')
        verify(helmClient).upgrade('releaseName', 'repoName/chart', [namespace: 'foo-namespace',
                                                                     version  : 'version',
                                                                     values   : valuesYaml.toString()])
    }

    @Test
    void 'Fails to deploy from git'() {
        DeploymentContext context = new ContextBuilder(createConfig()).build()

        def exception = shouldFail(RuntimeException) {
            createStrategy().deployFeature('http://repoURL',
                    'repoName',
                    'chart',
                    'version',
                    'namespace',
                    'releaseName',
                    Path.of('values.yaml'),
                    DeploymentStrategy.RepoType.GIT,
                    context,
                    null as RepositoryWorkspace)
        }

        assertThat(exception.message).isEqualTo('Unable to deploy helm chart via Helm CLI from Git URL, because helm does not support this out of the box.\n' +
                'Repo URL: http://repoURL')
    }

    protected HelmStrategy createStrategy() {
        return new HelmStrategy(helmClient)
    }

    private Config createConfig() {
        return new Config(application: new Config.ApplicationSchema(namePrefix: 'foo-'))
    }
}