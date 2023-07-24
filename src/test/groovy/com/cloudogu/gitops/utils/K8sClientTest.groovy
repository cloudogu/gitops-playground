package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Configuration
import groovy.yaml.YamlSlurper
import jakarta.inject.Provider
import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

class K8sClientTest {

    Map config = [
            application: [
                    namePrefix: "foo-"
            ]
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    K8sClient k8sClient = new K8sClient(commandExecutor, new FileSystemUtils(), new Provider<Configuration>() {
        @Override
        Configuration get() {
            new Configuration(config)
        }
    })

    @Test
    void 'Creates secret'() {
        k8sClient.createSecret('generic', 'my-secret', 'my-ns',
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic my-secret -n foo-my-ns --from-literal=key1=value1 --from-literal=key2=value2" +
                        " --dry-run=client -oyaml | kubectl apply -f-")
    }

    @Test
    void 'Creates secret without namespace'() {
        k8sClient.createSecret('generic', 'my-secret', new Tuple2('key1', 'value1'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic my-secret --from-literal=key1=value1 --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }

    @Test
    void 'Creates no secret when literals are missing'() {
        shouldFail(RuntimeException) {
            k8sClient.createSecret('generic', 'my-secret')
        }
    }

    @Test
    void 'Creates configmap from file'() {
        k8sClient.createConfigMapFromFile('my-map', 'my-ns', '/file')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create configmap my-map -n foo-my-ns --from-file=/file --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }

    @Test
    void 'Creates configmap without namespace'() {
        k8sClient.createConfigMapFromFile('my-map', '/file')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create configmap my-map --from-file=/file --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }

    @Test
    void 'Adds labels'() {
        k8sClient.label('secret', 'my-secret', 'my-ns',
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl label secret my-secret -n foo-my-ns --overwrite key1=value1 key2=value2")
    }

    @Test
    void 'Adds labels without namespace'() {
        k8sClient.label('secret', 'my-secret',
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl label secret my-secret --overwrite key1=value1 key2=value2")
    }

    @Test
    void 'Does not add label when key value pairs are missing'() {
        shouldFail(RuntimeException) {
            k8sClient.label('secret', 'my-secret')
        }
    }

    @Test
    void 'Patches'() {
        def expectedYaml = [a: 'b']
        k8sClient.patch('secret', 'my-secret', 'ns', expectedYaml)

        assertThat(commandExecutor.actualCommands[0]).startsWith("kubectl patch secret my-secret -n foo-ns --patch-file=")

        String patchFile = (commandExecutor.actualCommands[0] =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
        assertThat(parseActualYaml(patchFile)).isEqualTo(expectedYaml)
    }

    @Test
    void 'Patches without namespace'() {
        k8sClient.patch('secret', 'my-secret', [a: 'b'])

        assertThat(commandExecutor.actualCommands[0]).startsWith("kubectl patch secret my-secret --patch-file=")
    }

    @Test
    void 'Deletes'() {
        k8sClient.delete('secret', 'my-ns',
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))
        
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl delete secret -n foo-my-ns --ignore-not-found=true --selector=key1=value1 --selector=key2=value2")
    }

    @Test
    void 'Deletes without namespace'() {
        k8sClient.delete('secret',
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl delete secret --ignore-not-found=true --selector=key1=value1 --selector=key2=value2")
    }

    @Test
    void 'Does not add delete when selectors are missing'() {
        shouldFail(RuntimeException) {
            k8sClient.delete('secret')
        }
    }
    

    private Map parseActualYaml(String pathToYamlFile) {
        File yamlFile = new File(pathToYamlFile)
        def ys = new YamlSlurper()
        return ys.parse(yamlFile) as Map
    }
}
