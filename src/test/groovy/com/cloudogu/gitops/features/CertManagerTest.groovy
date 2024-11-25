package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.AirGappedUtils
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

class CertManagerTest {
    String chartVersion = "1.16.1"
    Config config = Config.fromMap([
            features: [
                    certManager: [
                            active: true,
                            helm  : [
                                    chart  : 'cert-manager',
                                    repoURL: 'https://charts.jetstack.io',
                                    version: chartVersion,
                            ],
                    ],
            ],
    ])

    Path temporaryYamlFile
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)

    @Test
    void 'Helm release is installed'() {
        createCertManager().install()

        verify(deploymentStrategy).deployFeature('https://charts.jetstack.io', 'cert-manager',
                'cert-manager', chartVersion, 'cert-manager',
                'cert-manager', temporaryYamlFile)
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createCertManager().install()

        assertThat(parseActualYaml()['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(parseActualYaml()['cainjector']['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(parseActualYaml()['webhook']['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void "is disabled via active flag"() {
        config.features.certManager.active = false
        createCertManager().install()
        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.application.mirrorRepos = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('cert-manager')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [version: chartVersion]
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createCertManager().install()

        def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('cert-manager')
        // check existing value, but its not used in deploy.
        assertThat(helmConfig.value.repoURL).isEqualTo('https://charts.jetstack.io')
        assertThat(helmConfig.value.version).isEqualTo(chartVersion)
        // important check: repoUrl is overridden with our values.
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'cert-manager', '.', chartVersion, 'cert-manager',
                'cert-manager', temporaryYamlFile, DeploymentStrategy.RepoType.GIT)
    }

    @Test
    void 'check images are overriddes'() {

        // Prep
        config.application.mirrorRepos = true
        // test values
        config.features.certManager.helm.image = "this.is.my.registry:30000/this.is.my.repository/myImage:1"
        config.features.certManager.helm.webhookImage = "this.is.my.registry:30000/this.is.my.repository/myWebhook:2"
        config.features.certManager.helm.cainjectorImage = "this.is.my.registry:30000/this.is.my.repository/myCainjectorImage:3"
        config.features.certManager.helm.acmeSolverImage = "this.is.my.registry:30000/this.is.my.repository/myAcmeSolverImage:4"
        config.features.certManager.helm.startupAPICheckImage = "this.is.my.registry:30000/this.is.my.repository/myStartupAPICheckImage:5"
        when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')
        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('cert-manager')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [version: chartVersion]
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
        new CertManager(config, new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")) // Path after template invocation

                return ret
            }
        }, deploymentStrategy, new K8sClientForTest(config), airGappedUtils)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}
