package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class RegistryTest {

    Map config = [
            registry   : [
                    internal    : true,
                    url         : '',
                    path        : '',
                    username    : '',
                    password    : '',
                    internalPort: ApplicationConfigurator.DEFAULT_REGISTRY_PORT,
                    helm        : [
                            chart  : 'docker-registry',
                            repoURL: 'https://charts.helm.sh/stable',
                            version: '1.9.4'
                    ]
            ],
            application: [
                    namePrefix: "foo-",
            ],
    ]
    K8sClientForTest k8sClient = new K8sClientForTest(config)
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(helmCommands)
    File temporaryYamlFile

    @Test
    void 'is disabled when external registry is configured'() {
        config.registry['internal'] = false

        createRegistry().install()

        assertThat(helmCommands.actualCommands).isEmpty()
        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'is installed'() {
        createRegistry().install()

        assertThat(parseActualYaml()['service']['nodePort']).isEqualTo(ApplicationConfigurator.DEFAULT_REGISTRY_PORT)
        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')
        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo(
                'helm repo add registry https://charts.helm.sh/stable')
        assertThat(helmCommands.actualCommands[1].trim()).startsWith(
                'helm upgrade -i docker-registry registry/docker-registry --create-namespace')
        assertThat(helmCommands.actualCommands[1].trim()).contains('--version 1.9.4')
        assertThat(helmCommands.actualCommands[1].trim()).contains("--values ${temporaryYamlFile}")
        assertThat(helmCommands.actualCommands[1].trim()).contains('--namespace foo-default')
        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'creates an additional service when different port is set'() {
        def expectedNodePort = ApplicationConfigurator.DEFAULT_REGISTRY_PORT as int + 1
        config['registry']['internalPort'] = expectedNodePort

        createRegistry().install()

        assertThat(k8sClient.commandExecutorForTest.actualCommands[0]).contains("--node-port $expectedNodePort")
    }
    
    private Registry createRegistry() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        new Registry(new Configuration(config), new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFile = ret.toFile()

                return ret
            }
        }, k8sClient, new HelmStrategy(new Configuration(config), helmClient))
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}
