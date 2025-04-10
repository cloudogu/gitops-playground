package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import java.nio.file.Files
import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

class ExternalSecretsOperatorTest {

    Config config = new Config(
            application: new Config.ApplicationSchema(namePrefix: "foo-"),
            registry: new Config.RegistrySchema(),
            features: new Config.FeaturesSchema(
                    secrets: new Config.SecretsSchema(active: true)))

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    K8sClientForTest k8sClient = new K8sClientForTest(config)
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    Path temporaryYamlFile

    @Test
    void "is disabled via active flag"() {
        config.features.secrets.active = false
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
                'foo-secrets',
                'external-secrets',
                temporaryYamlFile
        )

        assertThat(parseActualYaml()).doesNotContainKeys('resources')
        assertThat(parseActualYaml()).doesNotContainKey('imagePullSecrets')
        assertThat(parseActualYaml()).doesNotContainKey('certController')
        assertThat(parseActualYaml()).doesNotContainKey('webhook')

        assertThat(parseActualYaml()['installCRDs']).isNull()
    }

    @Test
    void 'Skips CRDs'() {
        config.application.skipCrds = true

        createExternalSecretsOperator().install()

        assertThat(parseActualYaml()['installCRDs']).isEqualTo(false)
    }

    @Test
    void 'helm release is installed with custom images'() {
        config.features.secrets.externalSecrets.helm =  new Config.SecretsSchema.ESOSchema.ESOHelmSchema([
                image              : 'localhost:5000/external-secrets/external-secrets:v0.6.1',
                certControllerImage: 'localhost:5000/external-secrets/external-secrets-certcontroller:v0.6.1',
                webhookImage       : 'localhost:5000/external-secrets/external-secrets-webhook:v0.6.1'
        ])
        createExternalSecretsOperator().install()


        def valuesYaml = parseActualYaml()
        assertThat(valuesYaml['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets')
        assertThat(valuesYaml['image']['tag']).isEqualTo('v0.6.1')

        assertThat(valuesYaml['certController']['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets-certcontroller')
        assertThat(valuesYaml['certController']['image']['tag']).isEqualTo('v0.6.1')

        assertThat(valuesYaml['webhook']['image']['repository']).isEqualTo('localhost:5000/external-secrets/external-secrets-webhook')
        assertThat(valuesYaml['webhook']['image']['tag']).isEqualTo('v0.6.1')
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createExternalSecretsOperator().install()

        assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(parseActualYaml()['webhook']['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(parseActualYaml()['certController']['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.application.mirrorRepos = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('external-secrets')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [version: '1.2.3']
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createExternalSecretsOperator().install()

        def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('external-secrets')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://charts.external-secrets.io')
        assertThat(helmConfig.value.version).isEqualTo('0.9.16')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.foo-scm-manager.svc.cluster.local/scm/repo/a/b',
                'external-secrets', '.', '1.2.3', 'foo-secrets',
                'external-secrets', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }

    @Test
    void 'deploys image pull secrets for proxy registry'() {
        config.registry.createImagePullSecrets = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'
        config.registry.proxyPassword = 'proxy-pw'
        config.features.secrets.externalSecrets.helm = new Config.SecretsSchema.ESOSchema.ESOHelmSchema( [
                certControllerImage: 'some:thing',
                webhookImage       : 'some:thing'
        ])

        createExternalSecretsOperator().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-secrets' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        assertThat(parseActualYaml()['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
        assertThat(parseActualYaml()['certController']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
        assertThat(parseActualYaml()['webhook']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
    }

    private ExternalSecretsOperator createExternalSecretsOperator() {
        new ExternalSecretsOperator(
                config,
                new FileSystemUtils() {
                    @Override
                    Path writeTempFile(Map mergeMap) {
                        def ret = super.writeTempFile(mergeMap)
                        temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
                        // Path after template invocation
                        return ret
                    }
                }, deploymentStrategy, k8sClient, airGappedUtils)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}