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
import static org.mockito.Mockito.*

class CertManagerTest {
    String chartVersion = "1.16.1"
    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
                    podResources: false,
                    mirrorRepos: false,
                    skipCrds: false
            ],
            registry: [
                    createImagePullSecrets: false
            ],
            scmm       : [
                    internal: true,
            ],
            features:[
                    monitoring: [
                            active: false
                    ],

                    certManager: [
                            active: true,

                            helm  : [
                                    chart: 'cert-manager',
                                    repoURL: 'https://charts.jetstack.io',
                                    version: chartVersion,
                                    values: [:],
                                    image: '',
                                    webhookImage: '',
                                    cainjectorImage: '',
                                    acmeSolverImage: '',
                                    startupAPICheckImage: '',
                            ],
                    ],
            ],
    ]

    CommandExecutorForTest k8sCommandExecutor = new CommandExecutorForTest()
    Path temporaryYamlFile
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)

    @Test
    void 'Helm release is installed'() {
        createCertManager().install()

        /* Assert one default value */
//        def actual = parseActualYaml()

        verify(deploymentStrategy).deployFeature('https://charts.jetstack.io', 'cert-manager',
                'cert-manager', chartVersion,'cert-manager',
                'cert-manager', temporaryYamlFile)

    }

    @Test
    void 'Set cert manager image'() {
        config.application['skipCrds'] = false
        config.features['certManager']['helm']['image'] = "this.is.my.registry:30000/this.is.my.repository/cert-manager-controller:latest"

        createCertManager().install()

        assertThat(parseActualYaml()['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/cert-manager-controller')
        assertThat(parseActualYaml()['image']['tag'] as String).isEqualTo('latest')
    }

    @Test
    void 'Sets pod resource limits and requests'() {

        config.application['podResources'] = true

        createCertManager().install()

        assertThat(parseActualYaml()['resources']as Map).containsKeys('limits', 'requests')
        assertThat(parseActualYaml()['cainjector']['resources']as Map).containsKeys('limits', 'requests')
        assertThat(parseActualYaml()['webhook']['resources']as Map).containsKeys('limits', 'requests')
    }


    @Test
    void "is disabled via active flag"() {
        config['features']['certManager']['active'] = false
        createCertManager().install()
        assertThat(temporaryYamlFile).isNull()
    }



    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.features['certManager']['active'] = true
        config.application['mirrorRepos'] = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Map))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application['localHelmChartFolder'] = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('cert-manager')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [ version     : chartVersion ]
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createCertManager().install()

        def helmConfig = ArgumentCaptor.forClass(Map)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('cert-manager')
        // check existing value, but its not used in deploy.
        assertThat(helmConfig.value.repoURL).isEqualTo('https://charts.jetstack.io')
        assertThat(helmConfig.value.version).isEqualTo(chartVersion)
        // important check: repoUrl is overridden with our values.
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'cert-manager', '.', chartVersion,'cert-manager',
                'cert-manager', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }

    @Test
    void 'check images are overriddes'() {

        // Prep
        config.features['certManager']['active'] = true
        config.application['mirrorRepos'] = true
        // test values
        config.features['certManager']['helm']['image'] = "this.is.my.registry:30000/this.is.my.repository/myImage:1"
        config.features['certManager']['helm']['webhookImage'] = "this.is.my.registry:30000/this.is.my.repository/myWebhook:2"
        config.features['certManager']['helm']['cainjectorImage'] = "this.is.my.registry:30000/this.is.my.repository/myCainjectorImage:3"
        config.features['certManager']['helm']['acmeSolverImage'] = "this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage:4"
        config.features['certManager']['helm']['startupAPICheckImage'] = "this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage:5"
        when(airGappedUtils.mirrorHelmRepoToGit(any(Map))).thenReturn('a/b')
        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application['localHelmChartFolder'] = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('cert-manager')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [ version     : chartVersion ]
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())
        createCertManager().install()

        def templateFile = parseActualYaml()


        // Cert-Manager
        assertThat(parseActualYaml()['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myImage')
        assertThat(parseActualYaml()['image']['tag'] as String).isEqualTo('1')
                // myWebhook
        assertThat(parseActualYaml()['webhook']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myWebhook')
        assertThat(parseActualYaml()['webhook']['image']['tag'] as String).isEqualTo('2')
                // cainjectorImage
        assertThat(parseActualYaml()['cainjector']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myCainjectorImage')
        assertThat(parseActualYaml()['cainjector']['image']['tag'] as String).isEqualTo('3')
                // myWebhook
        assertThat(parseActualYaml()['acmesolver']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage')
        assertThat(parseActualYaml()['acmesolver']['image']['tag'] as String).isEqualTo('4')
                // myWebhook
        assertThat(parseActualYaml()['startupapicheck']['image']['repository'] as String).isEqualTo('this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage')
        assertThat(parseActualYaml()['startupapicheck']['image']['tag'] as String).isEqualTo('5')



    }
    private CertManager createCertManager() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        def configuration = new Configuration(config)
        new CertManager(new Configuration(config), new FileSystemUtils() {
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
