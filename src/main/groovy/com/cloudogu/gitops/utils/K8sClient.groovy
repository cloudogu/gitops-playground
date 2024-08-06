package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Configuration
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Immutable
import groovy.util.logging.Slf4j
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Slf4j
@Singleton
class K8sClient {
    private static final String[] APPLY_FROM_STDIN = [ 'kubectl', 'apply', '-f-' ]

    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private Provider<Configuration> configurationProvider

    K8sClient(
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            Provider<Configuration> configurationProvider // This is lazy to enable bootstrapping the configuration
    ) {
        this.fileSystemUtils = fileSystemUtils
        this.configurationProvider = configurationProvider
        this.commandExecutor = commandExecutor
    }

    String getInternalNodeIp() {
        String node = waitForNode()

        // For k3d this is either the host's IP or the IP address of the k3d API server's container IP (when --bind-localhost=false)
        // Note that this might return multiple InternalIP (IPV4 and IPV6) - we assume the first one is IPV4 (break after first)
        String[] command = ["kubectl", "get", "$node", 
                            "--template='{{range .status.addresses}}{{ if eq .type \"InternalIP\" }}{{.address}}{{break}}{{end}}{{end}}'"]
        return commandExecutor.execute(command).stdOut
    }

    private String waitForNode() {
        String[] command1 = ['kubectl', 'get', 'node', '-oname']
        String[] command2 = ['head', '-n1']

        int maxTries = 120
        int sleepMillis = 1000
        int tryCount = 0
        String output = ""

        log.debug("Waiting for first node of the cluster to become ready")
        while (output.isEmpty() && tryCount < maxTries) {
            output = commandExecutor.execute(command1, command2).stdOut
            
            if (output.isEmpty()) {
                tryCount++
                log.debug("Still waiting for first node of the cluster to become ready (try $tryCount/$maxTries)")
                sleep(sleepMillis)
            }
        }

        if (output.isEmpty()) {
            throw new RuntimeException("Back up waiting for first node of the cluster to become ready after $maxTries tries")
        }
        log.debug("First node of the cluster is ready: $output")
        return output
    }

    String applyYaml(String yamlLocation) {
        commandExecutor.execute("kubectl apply -f $yamlLocation").stdOut
    }

    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createNamespace(String name) {
        def command1 = kubectl( 'create', 'namespace', "${getNamePrefix()}${name}")
                .dryRunOutputYaml()
                .build()
        
        commandExecutor.execute(command1, APPLY_FROM_STDIN)
    }

    /**
     * Overloaded method to accept a list of namespaces.
     */
    void createNamespace(List<String> names) {
        names.each { name ->
            createNamespace(name)
        }
    }

    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createSecret(String type, String name, String namespace = '', Tuple2... literals) {
        def command1 = kubectl( 'create', 'secret', type, name)
                .namespace(namespace)
                .addAllMandatory('--from-literal', literals)
                .dryRunOutputYaml()
                .build()
        
        commandExecutor.execute(command1, APPLY_FROM_STDIN)
    }
    
    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createConfigMapFromFile(String name, String namespace = '', String filePath) {
        def command1 = kubectl('create', 'configmap', name)
                .namespace(namespace)
                .mandatory('--from-file', filePath)
                .dryRunOutputYaml()
                .build()

        commandExecutor.execute(command1, APPLY_FROM_STDIN)
    }
    
    /**
     * Idempotent create, i.e. overwrites if exists.
     * 
     * @param tcp Port pairs can be specified as '<port>:<targetPort>'.
     */
    void createServiceNodePort(String name, String tcp, String nodePort = '', String namespace = '') {
        def command1 = kubectl('create', 'service', 'nodeport', name)
                .namespace(namespace)
                .mandatory('--tcp', tcp)
                .optional('--node-port', nodePort)
                .dryRunOutputYaml()
                .build()
        
        commandExecutor.execute(command1, APPLY_FROM_STDIN)
    }

    void label(String resource, String name, String namespace  = '', Tuple2... keyValues) {
        if (!keyValues) {
            throw new RuntimeException("Missing key-value-pairs")
        }
        String command =
                "kubectl label ${resource} ${name}${namespace ? " -n ${getNamePrefix()}${namespace}" : ''} " +
                        '--overwrite ' + // Make idempotent
                        keyValues.collect { "${it.v1}=${it.v2}"}.join(' ')
        commandExecutor.execute(command)
    }

    void patch(String resource, String name, String namespace  = '', String type = '', Map yaml) {
        // We're using a patch file here, instead of a patch JSON (--patch), because of quoting issues
        // ERROR c.c.gitops.utils.CommandExecutor - Stderr: error: unable to parse "'{\"stringData\":": yaml: found unexpected end of stream
        File patchYaml = File.createTempFile('gitops-playground-patch-yaml', '')
        fileSystemUtils.writeYaml(yaml, patchYaml)

        //  kubectl patch secret argocd-secret -p '{"stringData": { "admin.password": "'"${bcryptArgoCDPassword}"'"}}' || true
        String command =
                "kubectl patch ${resource} ${name}${namespace ? " -n ${getNamePrefix()}${namespace}" : ''}" +
                        (type ? " --type=$type" : '')+
                        " --patch-file=${patchYaml.absolutePath}"
        commandExecutor.execute(command)
    }

    void delete(String resource, String namespace  = '', Tuple2... selectors) {
        if (!selectors) {
            throw new RuntimeException("Missing selectors")
        }
        // kubectl delete secret -n argocd -l owner=helm,name=argocd
        String command =
                "kubectl delete ${resource}${namespace ? " -n ${getNamePrefix()}${namespace}" : ''}" +
                        ' --ignore-not-found=true ' + // Make idempotent
                        selectors.collect { "--selector=${it.v1}=${it.v2}"}.join(' ')

        commandExecutor.execute(command)
    }

    void delete(String resource, String namespace, String name) {
        String command =
                "kubectl delete ${resource}${namespace ? " -n ${getNamePrefix()}${namespace}" : ''}" +
                        " $name" +
                        ' --ignore-not-found=true ' // Make idempotent

        commandExecutor.execute(command)
    }

    List<CustomResource> getCustomResource(String resource) {
        String[] command = ["kubectl", "get", resource, "-A", "-o", "jsonpath={range .items[*]}{.metadata.namespace}{','}{.metadata.name}{'\\n'}{end}"]
        def result = commandExecutor.execute(command)

        if (!result.stdOut) {
            return []
        }

        result.stdOut.split("\n").collect {
            def parts = it.split(",")

            def prefix = getNamePrefix()
            assert(parts[0].startsWith(prefix))

            return new CustomResource(parts[0].substring(prefix.length()), parts[1])
        }
    }

    String getConfigMap(String mapName, String key) {
        String[] command = ["kubectl", "get", "configmap", mapName, "-o", "jsonpath={.data['"+key.replace(".", "\\.")+"']}"]
        def result = commandExecutor.execute(command, false)
        if (result.exitCode != 0) {
            throw new RuntimeException("Could not fetch configmap $mapName: ${result.stdErr}")
        }

        if (result.stdOut == "") {
            throw new RuntimeException("Could not fetch $key within config-map $mapName")
        }

        return result.stdOut
    }
    
    String getCurrentContext() {
        // When running inside a pod this might fail
        def output = commandExecutor.execute('kubectl config current-context', false)
        if (!output.stdOut) {
            output.stdOut = '(current context not set)'
        }
        return output.stdOut
    }

    private String getNamePrefix() {
        def config = configurationProvider.get().config

        return config.application['namePrefix'] as String
    }

    Kubectl kubectl(String ... args) {
        new Kubectl(args)
    }

    /**
     * Patches the nodePort of a specified port in a service.
     *
     * @param serviceName      The name of the service to patch.
     * @param namespace        The namespace of the service.
     * @param portName         The name of the port to patch.
     * @param newNodePort      The new nodePort value to set.
     *
     * @throws RuntimeException if an error occurs while patching the service.
     */
    void patchServiceNodePort(String serviceName, String namespace, String portName, int newNodePort) {
        if (!serviceName || !namespace || !portName || newNodePort <= 0) {
            throw new IllegalArgumentException("Service name, namespace, port name, and valid nodePort must be provided")
        }

        // Get the current service spec to find the index of the port to patch
        String[] getServiceCommand = new Kubectl("get", "service", serviceName)
                .namespace(namespace)
                .mandatory("-o", "json")
                .build()
        CommandExecutor.Output getServiceOutput = commandExecutor.execute(getServiceCommand)
        def serviceSpec = new JsonSlurper().parseText(getServiceOutput.stdOut)
        def ports = serviceSpec.spec.ports

        // Find the index of the port to patch
        def portIndex = ports.findIndexOf { it.name == portName }
        if (portIndex == -1) {
            throw new RuntimeException("Port with name ${portName} not found in service ${serviceName}.")
        }

        // Create the JSON patch for the specific port
        def patch = [
                [
                        op: "replace",
                        path: "/spec/ports/${portIndex}/nodePort",
                        value: newNodePort
                ]
        ]
        String patchJson = new JsonBuilder(patch).toString()

        // Apply the patch
        String[] patchCommand = new Kubectl("patch", "service", serviceName)
                .namespace(namespace)
                .mandatory("--type", "json")
                .mandatory("-p", patchJson)
                .build()
        CommandExecutor.Output patchOutput = commandExecutor.execute(patchCommand)

        log.debug("Service ${serviceName} in namespace ${namespace} successfully patched with nodePort ${newNodePort} for port ${portName}.")
    }

    /**
     * Waits until the specified resource reaches the desired phase.
     *
     * @param resourceType      The type of the Kubernetes resource (e.g., pod, deployment).
     * @param resourceName      The name of the specific resource.
     * @param namespace         The namespace of the resource.
     * @param desiredPhase      The desired phase to wait for (e.g., Running, Succeeded).
     * @param timeoutSeconds    The maximum time to wait for the desired phase in seconds (default is 60 seconds).
     * @param checkIntervalSeconds The interval between status checks in seconds (default is 1 seconds).
     *
     * @throws RuntimeException if the desired phase is not reached within the timeout period.
     */
    void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase, int timeoutSeconds = 60, int checkIntervalSeconds = 1) {
        if (!resourceType || !resourceName || !namespace || !desiredPhase) {
            throw new IllegalArgumentException("Resource type, name, namespace, and desired phase must be provided")
        }
        if (timeoutSeconds <= 0 || checkIntervalSeconds <= 0) {
            throw new IllegalArgumentException("Timeout and check interval must be greater than zero")
        }

        long startTime = System.currentTimeMillis()
        long endTime = startTime + (timeoutSeconds * 1000)

        while (System.currentTimeMillis() < endTime) {
            String[] command = new Kubectl("get", resourceType, resourceName)
                    .namespace(namespace)
                    .mandatory("-o", "jsonpath={.status.phase}")
                    .build()

            def output = commandExecutor.execute(command)
            String phase = output.stdOut.trim()
            if (phase == desiredPhase) {
                log.debug("Resource ${resourceType}/${resourceName} in namespace ${namespace} reached the desired phase: ${desiredPhase}")
                return
            }

            log.debug("Current phase: ${phase}. Waiting for phase: ${desiredPhase}...")
            sleep(checkIntervalSeconds * 1000)
        }

        String timeoutMessage = "Timeout reached. Resource ${resourceType}/${resourceName} in namespace ${namespace} did not reach the desired phase: ${desiredPhase} within ${timeoutSeconds} seconds."
        throw new RuntimeException(timeoutMessage)
    }

    @Immutable
    static class CustomResource {
        String namespace
        String name
    }
    
    private class Kubectl {
        private List<String> command = [ 'kubectl' ]
        
        Kubectl(String ... args) {
            command.addAll(args)
        }
        
        Kubectl namespace(String namespace) {
            if (namespace) {
                this.command += ['-n', getNamePrefix() + namespace ]
            }
            return this
        }

        Kubectl mandatory(String paramName, String value) {
            // Here we could assert that value != null. For historical reasons we don't, for now.
            this.command += [paramName, value ]
            return this
        }

        Kubectl addAllMandatory(String paramName, Tuple2... values) {
            if (!values) {
                throw new RuntimeException("Missing values for parameter '${paramName}' in command '${command.join(' ')}'")
            }
            values.each {command += [ paramName, "${it.v1}=${it.v2}".toString() ] }
            return this
        }

        Kubectl optional(String paramName, String value) {
            if (value) {
                this.command += [paramName, value ]
            }
            return this
        }
        
        Kubectl dryRunOutputYaml() {
            this.command += [ '--dry-run=client', '-oyaml' ]
            return this
        }
        
        String[] build() {
            this.command
        }
    }
}
