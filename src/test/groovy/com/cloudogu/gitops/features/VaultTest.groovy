package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.*
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class VaultTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
            ],
            features    : [
                    secrets   : [
                            active         : true,
                            vault: [
                                    mode: 'prod',
                                    helm: [
                                            chart  : 'vault',
                                            repoURL: 'https://vault-reg',
                                            version: '42.23.0'
                                    ]
                            ],
                    ],
                    argocd    : [
                            active: true
                    ]
            ],
    ]
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(helmCommands)
    K8sClientForTest k8sClient = new K8sClientForTest(config)
    File temporaryYamlFile

    @Test
    void 'is disabled via active flag'() {
        config['features']['secrets']['active'] = false
        createVault().install()
        assertThat(helmCommands.actualCommands).isEmpty()
        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'when not run remotely, set node port'() {
        config['application']['remote'] = false
        createVault().install()

        assertThat(parseActualYaml()['ui']['serviceType']).isEqualTo('NodePort')
        assertThat(parseActualYaml()['ui']['serviceNodePort']).isEqualTo(8200)
    }

    @Test
    void 'when run remotely, use service type loadbalancer'() {
        config['application']['remote'] = true
        createVault().install()

        assertThat(parseActualYaml()['ui']['serviceType']).isEqualTo('LoadBalancer')
        assertThat(parseActualYaml()['ui'] as Map).doesNotContainKey('serviceNodePort')
    }

    @Test
    void 'uses ingress if enabled'() {
        config['features']['secrets']['vault']['url'] = 'http://vault.local'
        createVault().install()


        def ingressYaml = parseActualYaml()['server']['ingress']
        assertThat(ingressYaml['enabled']).isEqualTo(true)
        assertThat((ingressYaml['hosts'] as List)[0]['host']).isEqualTo('vault.local')
    }

    @Test
    void 'does not use ingress by default'() {
        createVault().install()

        assertThat((parseActualYaml()['server'] as Map)).doesNotContainKey('ingress')
    }

    @Test
    void 'Dev mode can be enabled via config'() {
        config['features']['secrets']['vault']['mode'] = 'dev'
        createVault().install()

        def actualYaml = parseActualYaml()
        assertThat(actualYaml['server']['dev']['enabled']).isEqualTo(true)

        assertThat(actualYaml['server']['dev']['devRootToken']).isNotEqualTo('root')
        assertThat(actualYaml['server']['dev']['devRootToken']).isNotEqualTo(config['application']['password'])

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

        assertThat(k8sClient.commandExecutorForTest.actualCommands).hasSize(2)

        assertThat(k8sClient.commandExecutorForTest.actualCommands[0]).contains('kubectl create namespace foo-secrets')
        
        def createdConfigMapName = ((k8sClient.commandExecutorForTest.actualCommands[1] =~ /kubectl create configmap (\S*) .*/)[0] as List) [1]
        assertThat(actualVolumes[0]['configMap']['name']).isEqualTo(createdConfigMapName)

        assertThat(k8sClient.commandExecutorForTest.actualCommands[1]).contains('-n foo-secrets')
    }

    @Test
    void 'Dev mode can be enabled via config with argoCD disabled'() {
        config['features']['secrets']['vault']['mode'] = 'dev'
        config['features']['argocd']['active'] = false
        createVault().install()

        def actualYaml = parseActualYaml()
        List actualPostStart = (List) actualYaml['server']['postStart']
        assertThat(actualPostStart[2]).isEqualTo(
                'USERNAME=abc PASSWORD=123 ARGOCD=false /var/opt/scripts/dev-post-start.sh 2>&1 | tee /tmp/dev-post-start.log')
    }

    @Test
    void 'Prod mode can be enabled'() {
        config['features']['secrets']['vault']['mode'] = 'prod'
        createVault().install()

        def actualYaml = parseActualYaml()
        assertThat((actualYaml as Map)['server'] as Map).doesNotContainKey('dev')

        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'custom image is used'() {
        config['features']['secrets']['vault']['helm']['image'] = 'localhost:5000/hashicorp/vault:1.12.0'
        createVault().install()

        def actualYaml = parseActualYaml()
        assertThat(actualYaml['server']['image']['repository']).isEqualTo('localhost:5000/hashicorp/vault')
        assertThat(actualYaml['server']['image']['tag']).isEqualTo('1.12.0')
    }

    @Test
    void 'helm release is installed'() {
        createVault().install()

        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo(
                'helm repo add vault https://vault-reg')
        assertThat(helmCommands.actualCommands[1].trim()).startsWith(
                'helm upgrade -i vault vault/vault --create-namespace')
        assertThat(helmCommands.actualCommands[1].trim()).contains('--version 42.23.0')
        assertThat(helmCommands.actualCommands[1].trim()).contains(" --values ${temporaryYamlFile}")
        assertThat(helmCommands.actualCommands[1].trim()).contains('--namespace foo-secrets')
    }

    private Vault createVault() {
        def fileSystemUtils = new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFile = ret.toFile()

                return ret
            }
        }
        Vault vault = new Vault(new Configuration(config), fileSystemUtils, k8sClient, new HelmStrategy(new Configuration(config), helmClient))
        return vault
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}