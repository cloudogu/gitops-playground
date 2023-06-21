package com.cloudogu.gitops.features

import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat 

class ExternalSecretsOperatorTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false

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
    FileSystemUtils fileSystemUtils = new FileSystemUtils()

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
        assertThat(commandExecutor.actualCommands[1].trim()).isEqualTo(
                'helm upgrade -i external-secrets externalsecretsoperator/external-secrets --version 0.6.0' +
                        " --values ${fileSystemUtils.rootDir}/applications/cluster-resources/secrets/external-secrets/values.yaml --namespace secrets")
    }

    private ExternalSecretsOperator createExternalSecretsOperator() {
        new ExternalSecretsOperator(config, fileSystemUtils, new HelmStrategy(helmClient))
    }

}
