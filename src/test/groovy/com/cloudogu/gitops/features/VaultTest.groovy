package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config

import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.*
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import java.nio.file.Files
import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

class VaultTest {

    Config config = new Config(
            application: new Config.ApplicationSchema(
                    namePrefix: 'foo-',
            ),
            features: new Config.FeaturesSchema(
                    secrets: new Config.SecretsSchema(
                            active: true,
                    )
            )
    )

    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    K8sClientForTest k8sClient = new K8sClientForTest(config)
    File temporaryYamlFile

    @Test
    void 'is disabled via active flag'() {
        config.features.secrets.active = false
        createVault().install()
        assertThat(helmCommands.actualCommands).isEmpty()
        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'when not run remotely, set node port'() {
        createVault().install()

        assertThat(parseActualYaml()['ui']['serviceType']).isEqualTo('NodePort')
        assertThat(parseActualYaml()['ui']['serviceNodePort']).isEqualTo(8200)
    }

    @Test
    void 'when run remotely, use service type loadbalancer'() {
        config.application.remote = true
        createVault().install()

        assertThat(parseActualYaml()['ui']['serviceType']).isEqualTo('LoadBalancer')
        assertThat(parseActualYaml()['ui'] as Map).doesNotContainKey('serviceNodePort')
    }

    @Test
    void 'uses ingress if enabled'() {
        config.features.secrets.vault.url = 'http://vault.local'
        // Also set image to make sure ingress and image work at the same time under the server block
        config.features.secrets.vault.helm.image = 'localhost:5000/hashicorp/vault:1.12.0'
        createVault().install()

        def ingressYaml = parseActualYaml()['server']['ingress']
        assertThat(ingressYaml['enabled']).isEqualTo(true)
        assertThat((ingressYaml['hosts'] as List)[0]['host']).isEqualTo('vault.local')
    }

    @Test
    void 'does not use ingress by default'() {
        createVault().install()

        assertThat(parseActualYaml()).doesNotContainKey('server')
    }

    @Test
    void 'Dev mode can be enabled via config'() {
        config.features.secrets.vault.mode = 'dev'
        config.application.username = 'abc'
        config.application.password = '123'
        config.features.argocd.active = true

        def vault = createVault()

        // Simulate that the namespace does not exist (kubectl get returns a non-zero exit code)
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('Error from server (NotFound): namespaces "foo-secrets" not found', '', 1))

        vault.install()

        def actualYaml = parseActualYaml()
        assertThat(actualYaml['server']['dev']['enabled']).isEqualTo(true)

        assertThat(actualYaml['server']['dev']['devRootToken']).isNotEqualTo('root')
        assertThat(actualYaml['server']['dev']['devRootToken']).isNotEqualTo(config.application.password)

        List actualPostStart = (List) actualYaml['server']['postStart']
        assertThat(actualPostStart[0]).isEqualTo('/bin/sh')
        assertThat(actualPostStart[1]).isEqualTo('-c')

        assertThat(actualPostStart[2]).isEqualTo(
                'USERNAME=abc PASSWORD=123 ARGOCD=true /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log')

        List actualVolumes = actualYaml['server']['volumes'] as List
        List actualVolumeMounts = actualYaml['server']['volumeMounts'] as List
        assertThat(actualVolumes[0]['name']).isEqualTo(actualVolumeMounts[0]['name'])
        assertThat(actualVolumes[0]['configMap']['defaultMode']).isEqualTo(Integer.valueOf(0774))

        assertThat(actualVolumeMounts[0]['readOnly']).is(true)
        assertThat(actualPostStart[2] as String).contains(actualVolumeMounts[0]['mountPath'] as String + "/dev-post-start.sh")

        assertThat(k8sClient.commandExecutorForTest.actualCommands).hasSize(3)

        assertThat(k8sClient.commandExecutorForTest.actualCommands[0]).contains('kubectl get namespace foo-secrets')
        assertThat(k8sClient.commandExecutorForTest.actualCommands[1]).contains('kubectl create namespace foo-secrets')

        def createdConfigMapName = ((k8sClient.commandExecutorForTest.actualCommands[2] =~ /kubectl create configmap (\S*) .*/)[0] as List) [1]
        assertThat(actualVolumes[0]['configMap']['name']).isEqualTo(createdConfigMapName)

        assertThat(k8sClient.commandExecutorForTest.actualCommands[2]).contains('-n foo-secrets')
        assertThat(actualYaml['server'] as Map).doesNotContainKey('resources')
    }

    @Test
    void 'Dev mode can be enabled via config with argoCD disabled'() {
        config.features.secrets.vault.mode = 'dev'
        config.application.username = 'abc'
        config.application.password = '123'
        createVault().install()

        def actualYaml = parseActualYaml()
        List actualPostStart = (List) actualYaml['server']['postStart']
        assertThat(actualPostStart[2]).isEqualTo(
                'USERNAME=abc PASSWORD=123 ARGOCD=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log')
    }

    @Test
    void 'Prod mode can be enabled'() {
        config.features.secrets.vault.mode = 'prod'
        createVault().install()

        assertThat(parseActualYaml()).doesNotContainKey('server')

        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'custom image is used'() {
        config.features.secrets.vault.helm.image = 'localhost:5000/hashicorp/vault:1.12.0'
        createVault().install()

        def actualYaml = parseActualYaml()
        assertThat(actualYaml['server']['image']['repository']).isEqualTo('localhost:5000/hashicorp/vault')
        assertThat(actualYaml['server']['image']['tag']).isEqualTo('1.12.0')
    }

    @Test
    void 'helm release is installed'() {
        config.features.secrets.vault.helm = new Config.SecretsSchema.VaultSchema.VaultHelmSchema(
                chart: 'vault',
                repoURL: 'https://vault-reg',
                version: '42.23.0'
        )
        createVault().install()

        Path temporaryYamlFilePath = temporaryYamlFile.toPath()

        verify(deploymentStrategy).deployFeature(
                'https://vault-reg',
                'vault',
                'vault',
                '42.23.0',
                'secrets',
                'vault',
                temporaryYamlFilePath
        )

        assertThat(parseActualYaml()).doesNotContainKey('global')
    }

    @Test
    void 'helm release is installed in air-gapped mode'() {
        config.application.mirrorRepos = true
        config.features.secrets.vault.helm = new Config.SecretsSchema.VaultSchema.VaultHelmSchema(
                chart: 'vault',
                repoURL: 'https://vault-reg',
                version: '42.23.0'
        )

        when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path SourceChart = rootChartsFolder.resolve('vault')
        Files.createDirectories(SourceChart)

        Map ChartYaml = [version: '1.2.3']
        fileSystemUtils.writeYaml(ChartYaml, SourceChart.resolve('Chart.yaml').toFile())

        createVault().install()

        Path temporaryYamlFilePath = temporaryYamlFile.toPath()
        def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('vault')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://vault-reg')
        assertThat(helmConfig.value.version).isEqualTo('42.23.0')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'vault', '.', '1.2.3', 'secrets',
                'vault', temporaryYamlFilePath, DeploymentStrategy.RepoType.GIT)
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createVault().install()

        def actualYaml = parseActualYaml()
        assertThat(actualYaml['server']['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'deploys image pull secrets for proxy registry'() {
        config.registry.createImagePullSecrets = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'

        createVault().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-secrets' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        assertThat(parseActualYaml()['global']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
    }

    private Vault createVault() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        new Vault(config, new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")).toFile()
                return ret
            }
        }, k8sClient, deploymentStrategy, airGappedUtils)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}