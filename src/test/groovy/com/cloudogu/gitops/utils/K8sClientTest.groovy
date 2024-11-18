package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable

class K8sClientTest {

    Config config = new Config(application: new Config.ApplicationSchema(namePrefix: "foo-"))

    K8sClientForTest k8sClient = new K8sClientForTest( config)
    CommandExecutorForTest commandExecutor =  k8sClient.commandExecutorForTest

    @Test
    void 'Gets internal nodeIp'() {
        // waitForNode()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', 'node/k3d-gitops-playground-server-0', 0))
        // getInternalNodeIp()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '1.2.3.4', 0))

        def actualNodeIp = k8sClient.getInternalNodeIp()
        
        assertThat(actualNodeIp).isEqualTo('1.2.3.4')
        assertThat(commandExecutor.actualCommands[1]).isEqualTo(
                "kubectl get node/k3d-gitops-playground-server-0 " +
                        "--template='{{range .status.addresses}}{{ if eq .type \"InternalIP\" }}{{.address}}{{break}}{{end}}{{end}}'")
    }

    @Test
    void 'Gets internal nodeIp after waiting for node'() {
        // waitForNode()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '', 0))
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '', 0))
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', 'node/k3d-gitops-playground-server-0', 0))
        // getInternalNodeIp()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '1.2.3.4', 0))

        def actualNodeIp = k8sClient.getInternalNodeIp()

        assertThat(actualNodeIp).isEqualTo('1.2.3.4')
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
    void 'Creates imagePullSecret without namespace'() {
        k8sClient.createImagePullSecret('my-reg', 'host', 'user', 'pw')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                'kubectl create secret docker-registry my-reg' +
                ' --docker-server host --docker-username user --docker-password pw' +
                ' --dry-run=client -oyaml | kubectl apply -f-')
    }
    
    @Test
    void 'Creates imagePullSecret'() {
        k8sClient.createImagePullSecret('my-reg', 'ns', 'host', 'user', 'pw')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                'kubectl create secret docker-registry my-reg -n foo-ns' +
                ' --docker-server host --docker-username user --docker-password pw' +
                ' --dry-run=client -oyaml | kubectl apply -f-')
    }

    @Test
    void 'Creates no secret when literals are missing'() {
        def exception = shouldFail(RuntimeException) {
            k8sClient.createSecret('generic', 'my-secret')
        }
        assertThat(exception.message).isEqualTo('Missing values for parameter \'--from-literal\' in command \'kubectl create secret generic my-secret\'')
    }

    @Test
    void 'Ensure in secret creation, nullable String become empty string'() {

        def secret = k8sClient.createSecret("generic", "very-secret", new Tuple2('isnullbecomeempty', null))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic very-secret --from-literal isnullbecomeempty= --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
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

    @Test
    void 'Creates namespace when it does not exist'() {
        // Simulate that the namespace does not exist (kubectl get returns a non-zero exit code)
        commandExecutor.enqueueOutput(new CommandExecutor.Output('Error from server (NotFound): namespaces "foo-my-ns" not found', '', 1))

        // Attempt to create the namespace
        k8sClient.createNamespace('my-ns')

        // Assert that the correct kubectl command was issued to create the namespace
        assertThat(commandExecutor.actualCommands[1]).isEqualTo(
                "kubectl create namespace foo-my-ns")
    }

    @Test
    void 'Does not create namespace if it already exists'() {
        // Simulate that the namespace already exists (kubectl get returns a zero exit code)
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', '', 0))

        // Attempt to create the namespace
        k8sClient.createNamespace('my-ns')

        // Assert that no kubectl create command was issued except 'kubectl get namespace foo-my-ns'
        assertThat(commandExecutor.actualCommands.size()).is(1)
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl get namespace foo-my-ns")
    }

    @Test
    void 'Throws IllegalArgumentException when namespace name for Creation is null'() {
        // Attempt to create a namespace with a null name
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.createNamespace(null)
        }

        // Assert that the exception message is correct
        assertThat(exception.message).isEqualTo("Namespace name must be provided and cannot be null or empty.")
    }

    @Test
    void 'Throws IllegalArgumentException when namespace name for Creation is empty'() {
        // Attempt to create a namespace with an empty name
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.createNamespace('')
        }

        // Assert that the exception message is correct
        assertThat(exception.message).isEqualTo("Namespace name must be provided and cannot be null or empty.")
    }

    @Test
    void 'Throws RuntimeException when Namespace creation fails due to insufficient permissions'() {
        // Simulate Namespace does not exist
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', '', 1))
        // Simulate a permission error during namespace creation
        commandExecutor.enqueueOutput(new CommandExecutor.Output('Error from server (Forbidden): namespaces is forbidden', '', 1))

        // Attempt to create the namespace
        def exception = shouldFail(RuntimeException) {
            k8sClient.createNamespace('my-ns')
        }

        // Assert that the exception message is correct
        assertThat(exception.message).contains("Failed to create namespace foo-my-ns (possibly due to insufficient permissions)")
    }

    @Test
    void 'Throws RuntimeException on unexpected error during namespace creation'() {
        // Simulate Namespace does not exist
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', '', 1))
        // Simulate an unexpected error during namespace creation
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', 'Unexpected error', 1))

        // Attempt to create the namespace
        def exception = shouldFail(RuntimeException) {
            k8sClient.createNamespace('my-ns')
        }

        // Assert that the exception message is correct
        assertThat(exception.message).contains("Failed to create namespace foo-my-ns (possibly due to insufficient permissions)")
    }

    @Test
    void 'Patches nodePort successfully when all parameters are valid'() {
        // Simulate the output of the kubectl get service command
        def serviceJson = '''
    {
        "spec": {
            "ports": [
                {"name": "http", "nodePort": 30000},
                {"name": "https", "nodePort": 30001}
            ]
        }
    }'''
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', serviceJson, 0))

        // Attempt to patch the nodePort
        k8sClient.patchServiceNodePort('my-service', 'my-namespace', 'https', 32000)

        // Assert that the correct kubectl patch command was issued
        assertThat(commandExecutor.actualCommands[1]).isEqualTo(
                'kubectl patch service my-service -n foo-my-namespace --type json -p [{"op":"replace","path":"/spec/ports/1/nodePort","value":32000}]'
        )
    }
    @Test
    void 'Throws IllegalArgumentException when serviceName is null in patchServiceNodePort'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.patchServiceNodePort(null, 'my-namespace', 'https', 32000)
        }

        assertThat(exception.message).isEqualTo("Service name, namespace, port name, and valid nodePort must be provided")
    }

    @Test
    void 'Throws IllegalArgumentException when namespace is null in patchServiceNodePort'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.patchServiceNodePort('my-service', null, 'https', 32000)
        }

        assertThat(exception.message).isEqualTo("Service name, namespace, port name, and valid nodePort must be provided")
    }

    @Test
    void 'Throws IllegalArgumentException when portName is null'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.patchServiceNodePort('my-service', 'my-namespace', null, 32000)
        }

        assertThat(exception.message).isEqualTo("Service name, namespace, port name, and valid nodePort must be provided")
    }

    @Test
    void 'Throws IllegalArgumentException when newNodePort is not valid (less than 0)'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.patchServiceNodePort('my-service', 'my-namespace', 'https', -1)
        }

        assertThat(exception.message).isEqualTo("Service name, namespace, port name, and valid nodePort must be provided")
    }

    @Test
    void 'Throws RuntimeException when service does not contain the specified port'() {
        // Simulate the output of the kubectl get service command with no matching port
        def serviceJson = '''
    {
        "spec": {
            "ports": [
                {"name": "http", "nodePort": 30000}
            ]
        }
    }'''
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', serviceJson, 0))

        def exception = shouldFail(RuntimeException) {
            k8sClient.patchServiceNodePort('my-service', 'my-namespace', 'https', 32000)
        }

        assertThat(exception.message).isEqualTo("Port with name https not found in service my-service.")
    }

    @Test
    void 'Throws RuntimeException when kubectl patch command fails on Service NodePort'() {
        // Simulate the output of the kubectl get service command
        def serviceJson = '''
    {
        "spec": {
            "ports": [
                {"name": "http", "nodePort": 30000}
            ]
        }
    }'''
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', serviceJson, 0))

        // Simulate a failure in the kubectl patch command
        commandExecutor.enqueueOutput(new CommandExecutor.Output('Error from server (Forbidden): services "my-service" is forbidden', '', 1))

        def exception = shouldFail(RuntimeException) {
            k8sClient.patchServiceNodePort('my-service', 'my-namespace', 'http', 32000)
        }

        assertThat(exception.message).contains("Executing command failed: kubectl patch service my-service -n foo-my-namespace --type json -p [{\"op\":\"replace\",\"path\":\"/spec/ports/0/nodePort\",\"value\":32000}]")
    }

    @Test
    void 'Waits successfully until the resource reaches the desired phase'() {
        // Simulate the resource initially being in a different phase and then reaching the desired phase
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', 'Pending', 0))
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', 'Running', 0))

        // Attempt to wait for the resource to reach the desired phase
        k8sClient.waitForResourcePhase('pod', 'my-pod', 'my-namespace', 'Running')

        // Assert that the correct kubectl get command was issued and that the method returned successfully
        assertThat(commandExecutor.actualCommands).hasSize(2)
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                'kubectl get pod my-pod -n foo-my-namespace -o jsonpath={.status.phase}')
    }

    @Test
    void 'Throws IllegalArgumentException when resourceType is null'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.waitForResourcePhase(null, 'my-pod', 'my-namespace', 'Running')
        }

        assertThat(exception.message).isEqualTo("Resource type, name, namespace, and desired phase must be provided")
    }

    @Test
    void 'Throws IllegalArgumentException when resourceName is null'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.waitForResourcePhase('pod', null, 'my-namespace', 'Running')
        }

        assertThat(exception.message).isEqualTo("Resource type, name, namespace, and desired phase must be provided")
    }

    @Test
    void 'waitForResourcePhase Throws IllegalArgumentException when namespace is null'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.waitForResourcePhase('pod', 'my-pod', null, 'Running')
        }

        assertThat(exception.message).isEqualTo("Resource type, name, namespace, and desired phase must be provided")
    }

    @Test
    void 'Throws IllegalArgumentException when desiredPhase is null'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.waitForResourcePhase('pod', 'my-pod', 'my-namespace', null)
        }

        assertThat(exception.message).isEqualTo("Resource type, name, namespace, and desired phase must be provided")
    }

    @Test
    void 'Throws IllegalArgumentException when timeoutSeconds is less than or equal to zero'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.waitForResourcePhase('pod', 'my-pod', 'my-namespace', 'Running', 0, 1)
        }

        assertThat(exception.message).isEqualTo("Timeout and check interval must be greater than zero")
    }

    @Test
    void 'Throws IllegalArgumentException when checkIntervalSeconds is less than or equal to zero'() {
        def exception = shouldFail(IllegalArgumentException) {
            k8sClient.waitForResourcePhase('pod', 'my-pod', 'my-namespace', 'Running', 60, 0)
        }

        assertThat(exception.message).isEqualTo("Timeout and check interval must be greater than zero")
    }

    @Test
    void 'Throws RuntimeException when resource does not reach the desired phase within timeout'() {
        // Simulate the resource not reaching the desired phase within the timeout period
        commandExecutor.enqueueOutput(new CommandExecutor.Output('Pending', '', 0))
        commandExecutor.enqueueOutput(new CommandExecutor.Output('Pending', '', 0))

        // Attempt to wait for the resource to reach the desired phase
        def exception = shouldFail(RuntimeException) {
            k8sClient.waitForResourcePhase('pod', 'my-pod', 'my-namespace', 'Running', 2, 1)
        }

        // Assert that the correct exception message is returned
        assertThat(exception.message).contains("Timeout reached. Resource pod/my-pod in namespace my-namespace did not reach the desired phase: Running within 2 seconds.")
    }

    @Test
    void 'Handles immediate success without retrying'() {
        // Simulate the resource already being in the desired phase
        commandExecutor.enqueueOutput(new CommandExecutor.Output('', 'Running', 0))

        // Attempt to wait for the resource to reach the desired phase
        k8sClient.waitForResourcePhase('pod', 'my-pod', 'my-namespace', 'Running')

        // Assert that the command was executed only once and no retries occurred
        assertThat(commandExecutor.actualCommands).hasSize(1)
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                'kubectl get pod my-pod -n foo-my-namespace -o jsonpath={.status.phase}')
    }

    private Map parseActualYaml(String pathToYamlFile) {
        File yamlFile = new File(pathToYamlFile)
        def ys = new YamlSlurper()
        return ys.parse(yamlFile) as Map
    }
}
