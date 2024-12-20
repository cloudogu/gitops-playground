package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Files
import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

class MailhogTest {

    Config config = new Config(
            application: new Config.ApplicationSchema(
                    namePrefix: "foo-"),
            features: new Config.FeaturesSchema(
                    mail: new Config.MailSchema(
                            mailhog: true)
            ))

    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    Path temporaryYamlFile = null
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    K8sClientForTest k8sClient = new K8sClientForTest(config)

    @Test
    void "is disabled via active flag"() {
        config.features.mail.mailhog = false
        createMailhog().install()
        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'service type LoadBalancer when run remotely'() {
        config.application.remote = true
        createMailhog().install()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('LoadBalancer')
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config.application.remote = false
        createMailhog().install()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void 'uses ingress if enabled'() {
        config.features.mail.mailhogUrl = 'http://mailhog.local'
        createMailhog().install()

        def ingressYaml = parseActualYaml()['ingress']
        assertThat(ingressYaml['enabled']).isEqualTo(true)
        assertThat((ingressYaml['hosts'] as List)[0]['host']).isEqualTo('mailhog.local')
    }

    @Test
    void 'does not use ingress by default'() {
        createMailhog().install()

        assertThat(parseActualYaml()).doesNotContainKey('ingress')
    }

    @Test
    void 'Password and username can be changed'() {
        String expectedUsername = 'user42'
        String expectedPassword = '12345'
        config.application.username = expectedUsername
        config.application.password = expectedPassword
        createMailhog().install()

        String fileContents = parseActualYaml()['auth']['fileContents']
        String actualPasswordBcrypted = ((fileContents =~ /^[^:]*:(.*)$/)[0] as List)[1]
        new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)
        assertThat(new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)).isTrue()
                .withFailMessage("Expected password does not match actual hash")
    }

    @Test
    void 'When argocd disabled, mailhog is deployed imperatively via helm'() {
        config.features.argocd.active = false

        createMailhog().install()

        verify(deploymentStrategy).deployFeature(
                'https://codecentric.github.io/helm-charts',
                'mailhog',
                'mailhog',
                '5.0.1',
                'monitoring',
                'mailhog',
                temporaryYamlFile
        )

        assertThat(parseActualYaml()).doesNotContainKey('resources')
        assertThat(parseActualYaml()).doesNotContainKey('imagePullSecrets')
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createMailhog().install()

        assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'When argoCD enabled, mailhog is deployed natively via argoCD'() {
        config.features.argocd.active = true

        createMailhog().install()
    }

    @Test
    void 'Allows overriding the image'() {
        config.features.mail.helm.image = 'abc:42'

        createMailhog().install()
        assertThat(parseActualYaml()['image']['repository']).isEqualTo('abc')
        assertThat(parseActualYaml()['image']['tag']).isEqualTo(42)
    }

    @Test
    void 'Image is optional'() {
        config.features.mail.helm.image = ''

        createMailhog().install()
        assertThat(parseActualYaml()['image']).isNull()

        config.features.mail.helm.image = null

        createMailhog().install()
        assertThat(parseActualYaml()['image']).isNull()
    }

    @Test
    void 'custom values are injected correctly'() {
        config.features.mail.helm.values = [
                "containerPort": [
                        "http": [
                                "port": 9849003 //huge impossible port so it will not match any other configs
                        ]
                ]
        ]
        createMailhog().install()
        assertThat(parseActualYaml()['containerPort'] as String).contains('9849003')

    }


    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.application.mirrorRepos = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('mailhog')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [version: '1.2.3']
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createMailhog().install()

        def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('mailhog')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://codecentric.github.io/helm-charts')
        assertThat(helmConfig.value.version).isEqualTo('5.0.1')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'mailhog', '.', '1.2.3', 'monitoring',
                'mailhog', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }

    @Test
    void 'deploys image pull secrets for proxy registry'() {
        config.registry.createImagePullSecrets = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'

        createMailhog().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-monitoring' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        assertThat(parseActualYaml()['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
    }

    private Mailhog createMailhog() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        new Mailhog(config, new FileSystemUtils() {
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
