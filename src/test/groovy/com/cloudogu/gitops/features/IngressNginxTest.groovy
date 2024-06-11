package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.TemplatingEngine
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

class IngressNginxTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
                    podResources: false,
                    mirrorRepos: false
            ],
            scmm       : [
                    internal: true,
                    protocol: 'https',
                    host: 'abc',
                    username: '',
                    password: ''
            ],
            features:[
                    ingressNginx: [
                            active: true,
                            helm  : [
                                    chart: 'ingress-nginx',
                                    repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                    version: '4.8.2',
                                    values : [:]
                            ],
                    ],
            ],
    ]

    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)

    CommandExecutorForTest k8sCommandExecutor = new CommandExecutorForTest()
    Path temporaryYamlFile
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)

    @Test
    void 'Helm release is installed'() {
        createIngressNginx().install()

        /* Assert one default value */
        def actual = parseActualYaml()
        assertThat(actual['controller']['replicaCount']).isEqualTo(2)

        verify(deploymentStrategy).deployFeature('https://kubernetes.github.io/ingress-nginx', 'ingress-nginx',
                'ingress-nginx', '4.8.2','ingress-nginx',
                'ingress-nginx', temporaryYamlFile)
        assertThat(parseActualYaml()['controller']['resources']).isNull()
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application['podResources'] = true

        createIngressNginx().install()

        assertThat(parseActualYaml()['controller']['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'When Ingress-Nginx is not enabled, ingress-nginx-helm-values yaml has no content'() {
        config.features['ingressNginx']['active'] = false

        createIngressNginx().install()

        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'additional helm values merged with default values'() {
        config['features']['ingressNginx']['helm']['values'] = [
                controller: [
                        replicaCount: 42,
                        span: '7,5',
                   ]
        ]

        createIngressNginx().install()
        def actual = parseActualYaml()

        assertThat(actual['controller']['replicaCount']).isEqualTo(42)
        assertThat(actual['controller']['span']).isEqualTo('7,5')
    }


    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.features['ingressNginx']['active'] = true
        config.application['mirrorRepos'] = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Map))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application['localHelmChartFolder'] = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('ingress-nginx')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [ version     : '1.2.3' ]
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createIngressNginx().install()

        def helmConfig = ArgumentCaptor.forClass(Map)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('ingress-nginx')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://ingress-nginx')
        assertThat(helmConfig.value.version).isEqualTo('4.8.2')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'ingress-nginx', '.', '1.2.3','ingress-nginx',
                'ingress-nginx', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }

    private IngressNginx createIngressNginx() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        def configuration = new Configuration(config)
        new IngressNginx(new Configuration(config), new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")) // Path after template invocation

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
