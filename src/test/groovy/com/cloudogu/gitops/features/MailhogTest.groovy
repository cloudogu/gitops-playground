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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.nio.file.Files
import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

class MailhogTest {

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
                    argocd    : [
                            active: false,
                    ],
                    mail      : [
                            mailhog: true,
                            helm  : [
                                    chart  : 'mailhog',
                                    repoURL: 'https://mailhog',
                                    version: '5.0.1',
                                    image: ''
                            ]
                    ]
            ],
    ]
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    Path temporaryYamlFile = null
    CommandExecutorForTest k8sCommandExecutor = new CommandExecutorForTest()
    FileSystemUtils fileSystemUtils = new FileSystemUtils()

    @Test
    void "is disabled via active flag"() {
        config['features']['mail']['mailhog'] = false
        createMailhog().install()
        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'service type LoadBalancer when run remotely'() {
        config['application']['remote'] = true
        createMailhog().install()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('LoadBalancer')
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createMailhog().install()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void 'uses ingress if enabled'() {
        config.features['mail']['mailhogUrl'] = 'http://mailhog.local'
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
        config['application']['username'] = expectedUsername
        config['application']['password'] = expectedPassword
        createMailhog().install()

        String fileContents = parseActualYaml()['auth']['fileContents']
        String actualPasswordBcrypted = ((fileContents =~ /^[^:]*:(.*)$/)[0] as List)[1]
        new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)
        assertThat(new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)).isTrue()
                .withFailMessage("Expected password does not match actual hash")
    }
    
    @Test
    void 'When argocd disabled, mailhog is deployed imperatively via helm'() {
        config.features['argocd']['active'] = false

        createMailhog().install()

        assertThat(commandExecutor.actualCommands[0].trim()).isEqualTo(
                'helm repo add mailhog https://codecentric.github.io/helm-charts')
        assertThat(commandExecutor.actualCommands[1].trim()).startsWith(
                'helm upgrade -i mailhog mailhog/mailhog --create-namespace')
        assertThat(commandExecutor.actualCommands[1].trim()).contains('--version 5.0.1')
        assertThat(commandExecutor.actualCommands[1].trim()).contains('--namespace foo-monitoring')

        assertThat(parseActualYaml()).doesNotContainKey('resources')
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application['podResources'] = true

        createMailhog().install()

        assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'When argoCD enabled, mailhog is deployed natively via argoCD'() {
        config.features['argocd']['active'] = true

        createMailhog().install()
    }
    
    @Test
    void 'Allows overriding the image'() {
        config['features']['mail']['helm']['image'] = 'abc'

        createMailhog().install()
        assertThat(parseActualYaml()['image']['repository']).isEqualTo('abc')
    }
    
    @Test
    void 'Allows overriding the image with tag'() {
        config['features']['mail']['helm']['image'] = 'abc:42'
        
        createMailhog().install()
        assertThat(parseActualYaml()['image']['repository']).isEqualTo('abc')
        assertThat(parseActualYaml()['image']['tag']).isEqualTo(42)
    }
    
    @Test
    void 'Image is optional'() {
        config['features']['mail']['helm']['image'] = ''

        createMailhog().install()
        assertThat(parseActualYaml()['image']).isNull()
        
        config['features']['mail']['helm']['image'] = null

        createMailhog().install()
        assertThat(parseActualYaml()['image']).isNull()
    }

    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.application['mirrorRepos'] = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Map))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application['localHelmChartFolder'] = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('mailhog')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [ version     : '1.2.3' ]
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createMailhog().install()

        def helmConfig = ArgumentCaptor.forClass(Map)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('mailhog')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://mailhog')
        assertThat(helmConfig.value.version).isEqualTo('5.0.1')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'mailhog', '.', '1.2.3','monitoring',
                'mailhog', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }

    protected void assertMailhogInstalledImperativelyViaHelm() {

        verify(deploymentStrategy).deployFeature(
                'https://mailhog',
                'mailhog', 'mailhog', '5.0.1', 'monitoring',
                'mailhog', temporaryYamlFile)
    }

    private Mailhog createMailhog() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        def configuration = new Configuration(config)
        new Mailhog(new Configuration(config), new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                Path ret = super.copyToTempDir(filePath)
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
                // Path after template invocation
                return ret
            }
        }, deploymentStrategy, new K8sClient(k8sCommandExecutor, new FileSystemUtils(), new Provider<Configuration>() {
            @Override
            Configuration get() {
                configuration
            }
        }), airGappedUtils)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}
