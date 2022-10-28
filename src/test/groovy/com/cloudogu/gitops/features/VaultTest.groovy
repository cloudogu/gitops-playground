package com.cloudogu.gitops.features

import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat 

class VaultTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false

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
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(commandExecutor)
    File temporaryYamlFile
    
    @Test
    void "is disabled via active flag"() {
        config['features']['secrets']['active'] = false
        createVault().install()
        assertThat(commandExecutor.actualCommands).isEmpty()
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createVault().install()

        assertThat(parseActualYaml()['ui']['serviceType']).isEqualTo('NodePort')
    }
    
    @Test
    void 'Dev mode can be enabled via config'() {
        config['features']['secrets']['vault']['mode'] = 'dev'
        createVault().install()

        assertThat(parseActualYaml()['server']['dev']['enabled']).isEqualTo(true)
    }

    @Test
    void 'helm release is installed'() {
        createVault().install()

        assertThat(commandExecutor.actualCommands[0].trim()).isEqualTo(
                'helm repo add Vault https://vault-reg')
        assertThat(commandExecutor.actualCommands[1].trim()).isEqualTo(
                'helm upgrade -i vault Vault/vault --version=42.23.0' +
                        " --values ${temporaryYamlFile} --namespace secrets")
    }
    
    private Vault createVault() {
        Vault vault = new Vault(config, new FileSystemUtils(), helmClient)
        temporaryYamlFile = vault.tmpHelmValues
        return vault
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}
