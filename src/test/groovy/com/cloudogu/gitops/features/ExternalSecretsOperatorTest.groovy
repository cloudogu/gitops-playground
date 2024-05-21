package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class ExternalSecretsOperatorTest {

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
                            externalSecrets: [
                                    helm: [
                                            chart  : 'external-secrets',
                                            repoURL: 'https://charts.external-secrets.io',
                                            version: '0.6.0'
                                    ]
                            ],
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(commandExecutor)
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

        assertThat(commandExecutor.actualCommands[0].trim()).isEqualTo(
                'helm repo add externalsecretsoperator https://charts.external-secrets.io')
        assertThat(commandExecutor.actualCommands[1].trim()).startsWith(
                'helm upgrade -i external-secrets externalsecretsoperator/external-secrets --create-namespace')
        assertThat(commandExecutor.actualCommands[1].trim()).contains('--version 0.6.0')
        assertThat(commandExecutor.actualCommands[1].trim()).contains("--values $temporaryYamlFile")
        assertThat(commandExecutor.actualCommands[1].trim()).contains('--namespace foo-secrets')
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

    private ExternalSecretsOperator createExternalSecretsOperator() {
        new ExternalSecretsOperator(
                new Configuration(config),
                new FileSystemUtils() {
                    @Override
                    Path copyToTempDir(String filePath) {
                        temporaryYamlFile = super.copyToTempDir(filePath)
                        return temporaryYamlFile
                    }
                },
                new HelmStrategy(new Configuration(config), helmClient)
        )
    }

    private parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}
