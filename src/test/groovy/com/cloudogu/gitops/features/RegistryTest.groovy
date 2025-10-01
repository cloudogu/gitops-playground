package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.kubernetes.HelmClient
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static com.cloudogu.gitops.config.Config.*

class RegistryTest {

    K8sClientForTest k8sClient
    CommandExecutorForTest helmCommands
    HelmClient helmClient
    Path temporaryYamlFile

    @Test
    void 'is disabled when external registry is configured'() {
        createRegistry().install()

        assertThat(helmCommands.actualCommands).isEmpty()
        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'is installed'() {
        createRegistry(new RegistrySchema(active: true)).install()

        assertThat(parseActualYaml()['service']['nodePort']).isEqualTo(DEFAULT_REGISTRY_PORT)
        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')
        assertThat(helmCommands.actualCommands[0].trim()).startsWith(
                'helm repo add registry')
        assertThat(helmCommands.actualCommands[1].trim()).startsWith(
                'helm upgrade -i docker-registry registry/docker-registry --create-namespace')
        assertThat(helmCommands.actualCommands[1].trim()).contains('--version')
        assertThat(helmCommands.actualCommands[1].trim()).contains("--values ${temporaryYamlFile}")
        assertThat(helmCommands.actualCommands[1].trim()).contains('--namespace foo-registry')
        assertThat(k8sClient.commandExecutorForTest.actualCommands).isEmpty()
    }

    @Test
    void 'creates an additional service when different port is set'() {
        def expectedNodePort = DEFAULT_REGISTRY_PORT as int + 1
        def registryConfig = new RegistrySchema(active: true, internalPort: expectedNodePort)
        
        createRegistry(registryConfig).install()

        assertThat(k8sClient.commandExecutorForTest.actualCommands[0]).contains("--node-port $expectedNodePort")
    }

    @Test
    void 'inject custom value into chart'() {
        def registryConfig = new RegistrySchema(active: true,
                helm: new HelmConfigWithValues(
                        chart: 'test',
                        values: [
                                service: [
                                        type: 'NodePortTest'
                                ],
                                customValue: 'testinjectionValue'
                        ]
                )
        )

        createRegistry(registryConfig).install()
        assertThat(parseActualYaml()['service'] as String).contains('NodePortTest')
        assertThat(parseActualYaml()['customValue'] as String).contains('testinjectionValue')
    }

    private Registry createRegistry(RegistrySchema registryConfig = new RegistrySchema()) {
        def config = new Config(
                application: new ApplicationSchema(namePrefix: 'foo-'),
                registry: registryConfig
        )
        k8sClient = new K8sClientForTest(config)
        helmCommands = new CommandExecutorForTest()
        helmClient = new HelmClient(helmCommands)

        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        new Registry(config, new FileSystemUtils() {
            @Override
            Path writeTempFile(Map mergeMap) {
                def ret = super.writeTempFile(mergeMap)
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
                // Path after template invocation
                return ret
            }
        }, k8sClient, new HelmStrategy(config, helmClient))
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }

}