package com.cloudogu.gitops.utils


import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat 

class K8sClientTest {

    Map config = [
            application: [
                    namePrefix: "foo-"
            ]
    ]
    K8sClientForTest k8sClient = new K8sClientForTest(config)
    CommandExecutorForTest commandExecutor =  k8sClient.commandExecutorForTest

    @Test
    void 'Creates namespace'() {
        k8sClient.createNamespace('my-ns')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create namespace foo-my-ns" +
                        " --dry-run=client -oyaml | kubectl apply -f-")
    }
    
    @Test
    void 'Creates secret'() {
        k8sClient.createSecret('generic', 'my-secret', 'my-ns',
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic my-secret -n foo-my-ns --from-literal key1=value1 --from-literal key2=value2" +
                        " --dry-run=client -oyaml | kubectl apply -f-")
    }

    @Test
    void 'Creates secret without namespace'() {
        k8sClient.createSecret('generic', 'my-secret', new Tuple2('key1', 'value1'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic my-secret --from-literal key1=value1 --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }

    @Test
    void 'Creates no secret when literals are missing'() {
        def exception = shouldFail(RuntimeException) {
            k8sClient.createSecret('generic', 'my-secret')
        }
        assertThat(exception.message).isEqualTo('Missing values for parameter \'--from-literal\' in command \'kubectl create secret generic my-secret\'')
    }

    @Test
    void 'Creates configmap from file'() {
        k8sClient.createConfigMapFromFile('my-map', 'my-ns', '/file')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create configmap my-map -n foo-my-ns --from-file /file --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }
    
    @Test
    void 'Creates configmap without namespace'() {
        k8sClient.createConfigMapFromFile('my-map', '/file')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create configmap my-map --from-file /file --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }

    @Test
    void 'Creates service type nodePort'() {
        k8sClient.createServiceNodePort('my-svc', '42:23', '32000', 'my-ns')
        
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                'kubectl create service nodeport my-svc -n foo-my-ns --tcp 42:23 --node-port 32000' +
                        ' --dry-run=client -oyaml | kubectl apply -f-')
    }

    @Test
    void 'Creates service type nodePort without namespace and explicit nodePort'() {
        k8sClient.createServiceNodePort('my-svc', '42:23')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                'kubectl create service nodeport my-svc --tcp 42:23' +
                        ' --dry-run=client -oyaml | kubectl apply -f-')
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
        def exception = shouldFail(RuntimeException) {
            k8sClient.label('secret', 'my-secret')
        }
        assertThat(exception.message).isEqualTo('Missing key-value-pairs')
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
    void 'Patches with type merge'() {
        k8sClient.patch('secret', 'my-secret', '', 'merge', [a: 'b'])

        assertThat(commandExecutor.actualCommands[0]).startsWith("kubectl patch secret my-secret --type=merge --patch-file=")
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
        def exception = shouldFail(RuntimeException) {
            k8sClient.delete('secret')
        }
        assertThat(exception.message).isEqualTo('Missing selectors')
    }

    @Test
    void 'Gets custom resources with name prefix'() {
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', "foo-namespace,name\nfoo-namespace2,name2", 0))
        def result = k8sClient.getCustomResource('foo')

        assertThat(result).isEqualTo([new K8sClient.CustomResource('namespace', 'name'), new K8sClient.CustomResource('namespace2', 'name2')])
    }

    @Test
    void 'fetches config map sucessfully'() {
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', "the-file-content", 0))
        def map = k8sClient.getConfigMap("the-map", "file.yaml")

        assertThat(map, "the-file-content")
    }

    @Test
    void 'errors when config map does not exist'() {
        commandExecutor.enqueueOutput(new CommandExecutor.Output("Error from server (NotFound): configmaps \"the-map\" not found", "", 1))
        def exception = shouldFail() {
            k8sClient.getConfigMap("the-map", "file.yaml")
        }
        assertThat(exception.message).isEqualTo("Could not fetch configmap the-map: Error from server (NotFound): configmaps \"the-map\" not found")
    }

    @Test
    void 'errors when file does not exist'() {
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', '', 0))
        def exception = shouldFail() {
            k8sClient.getConfigMap("the-map", "file.yaml")
        }
        assertThat(exception.message).isEqualTo('Could not fetch file.yaml within config-map the-map')
    }
    
    @Test
    void 'returns current context'() {
        def expectedOutput = 'k3d-something'
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', expectedOutput, 0))
        
        assertThat(k8sClient.currentContext).isEqualTo(expectedOutput)
    }
    
    @Test
    void 'returns useful information, even if current context is not set'() {
        def expectedOutput = ''
        commandExecutor.enqueueOutput(new CommandExecutor.Output('error: current-context is not set', expectedOutput, 1))
        
        assertThat(k8sClient.currentContext).isEqualTo('(current context not set)')
    }

    private Map parseActualYaml(String pathToYamlFile) {
        File yamlFile = new File(pathToYamlFile)
        def ys = new YamlSlurper()
        return ys.parse(yamlFile) as Map
    }
}
