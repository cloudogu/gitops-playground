package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import groovy.yaml.YamlSlurper
import jakarta.inject.Provider
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

import java.nio.file.Files
import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

class ExternalSecretsOperatorTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
                    podResources : false,
                    mirrorRepos: false
            ],
            scmm       : [
                    internal: true,
                    protocol: 'https',
                    host: 'abc',
                    username: '',
                    password: ''
            ],
            features    : [
                    secrets   : [
                            active         : true,
                            externalSecrets: [
                                    helm: [
                                            chart  : 'external-secrets',
                                            repoURL: 'https://charts.external-secrets.io',
                                            version: '0.9.16'
                                    ]
                            ],
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    CommandExecutorForTest k8sCommandExecutor = new CommandExecutorForTest()
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    Path temporaryYamlFile

    @Test
    void "is disabled via active flag"() {
        config['features']['secrets']['active'] = false
        createExternalSecretsOperator().install()
        assertThat(commandExecutor.actualCommands).isEmpty()
    }

    @Test
    void 'helm release is installed'() {
        createExternalSecretsOperator().install()

        verify(deploymentStrategy).deployFeature(
                'https://charts.external-secrets.io',
                'externalsecretsoperator',
                'external-secrets',
                '0.9.16',
                'secrets',
                'external-secrets',
                temporaryYamlFile
        )

        assertThat(parseActualStackYaml()).doesNotContainKeys('resources')
        assertThat(parseActualStackYaml()).doesNotContainKey('certController')
        assertThat(parseActualStackYaml()).doesNotContainKey('webhook')
    }

    @Test
    void 'helm release is installed with custom images'() {
        config['features']['secrets']['externalSecrets']['helm'] = [
                image              : 'localhost:5000/external-secrets/external-secrets:v0.6.1',
                certControllerImage: 'localhost:5000/external-secrets/external-secrets-certcontroller:v0.6.1',
                webhookImage       : 'localhost:5000/external-secrets/external-secrets-webhook:v0.6.1'
        ]
        createExternalSecretsOperator().install()


        def valuesYaml = parseActualStackYaml()
        assertThat(valuesYaml['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets')
        assertThat(valuesYaml['image']['tag']).isEqualTo('v0.6.1')

        assertThat(valuesYaml['certController']['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets-certcontroller')
        assertThat(valuesYaml['certController']['image']['tag']).isEqualTo('v0.6.1')

        assertThat(valuesYaml['webhook']['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets-webhook')
        assertThat(valuesYaml['webhook']['image']['tag']).isEqualTo('v0.6.1')
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application['podResources'] = true

        createExternalSecretsOperator().install()

        assertThat(parseActualStackYaml()['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(parseActualStackYaml()['webhook']['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(parseActualStackYaml()['certController']['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.application['mirrorRepos'] = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Map))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application['localHelmChartFolder'] = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('external-secrets')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [ version: '1.2.3' ]
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createExternalSecretsOperator().install()

        def helmConfig = ArgumentCaptor.forClass(Map)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('external-secrets')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://charts.external-secrets.io')
        assertThat(helmConfig.value.version).isEqualTo('0.9.16')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'external-secrets', '.', '1.2.3','secrets',
                'external-secrets', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }


    private ExternalSecretsOperator createExternalSecretsOperator() {
        def configuration = new Configuration(config)
        new ExternalSecretsOperator(
                new Configuration(config),
                new FileSystemUtils() {

                    @Override
                    Path createTempFile() {
                        temporaryYamlFile = super.createTempFile()
                        return temporaryYamlFile
            }
        }, deploymentStrategy, new K8sClient(k8sCommandExecutor, new FileSystemUtils(), new Provider<Configuration>() {
            @Override
            Configuration get() {
                configuration
            }
        }), airGappedUtils)
    }

    private Map parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}
