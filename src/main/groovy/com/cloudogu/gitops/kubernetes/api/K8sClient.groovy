package com.cloudogu.gitops.kubernetes.api

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Immutable
import groovy.util.logging.Slf4j
import io.fabric8.kubernetes.api.model.Secret
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Slf4j
@Singleton
class K8sClient {
    private static final String[] APPLY_FROM_STDIN = ['kubectl', 'apply', '-f-']

    protected int SLEEPTIME = 1000
    protected int DEFAULT_RETRIES = 120

    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private Provider<Config> configProvider
    public K8sJavaApiClient k8sJavaApiClient

    K8sClient(
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            Provider<Config> configProvider
    ) {
        this.fileSystemUtils = fileSystemUtils
        this.commandExecutor = commandExecutor
        this.configProvider = configProvider
        this.k8sJavaApiClient = new K8sJavaApiClient()
    }

    private String waitForOutput(String[] command, String[] additionalCommand, String logMessage, String failureMessage, int maxTries = DEFAULT_RETRIES) {
        int tryCount = 0
        String output = ""

        log.debug(logMessage)
        while (output.isEmpty() && tryCount < maxTries) {
            if (!additionalCommand) {
                output = commandExecutor.execute(command).stdOut
            } else {
                output = commandExecutor.execute(command, additionalCommand).stdOut
            }

            if (output.isEmpty()) {
                tryCount++
                log.debug("Still waiting... (try $tryCount/$maxTries)")
                sleep(SLEEPTIME)
            }
        }

        if (output.isEmpty()) {
            throw new RuntimeException(failureMessage)
        }

        return output
    }

    private String waitForOutput(String[] command, String logMessage, String failureMessage, int maxTries = DEFAULT_RETRIES) {
        waitForOutput(command, null, logMessage, failureMessage, maxTries)
    }

    String waitForInternalNodeIp() {
        String node = waitForNode()
        // For k3d this is either the host's IP or the IP address of the k3d API server's container IP (when --bind-localhost=false)
        // Note that this might return multiple InternalIP (IPV4 and IPV6) - we assume the first one is IPV4 (break after first)
        String[] command = ["kubectl", "get", "$node",
                            "--template='{{range .status.addresses}}{{ if eq .type \"InternalIP\" }}{{.address}}{{break}}{{end}}{{end}}'"]
        String output = waitForOutput(
                command,
                "Waiting for internal IP of node $node",
                "Failed to retrieve internal node IP"
        )

        log.debug("Internal IP of node $node: $output")
        return output
    }

    String waitForNodePort(String serviceName, String namespace) {

        String[] command = new Kubectl("get", "service", serviceName)
                .namespace(namespace)
                .mandatory("-o", "jsonpath={.spec.ports[0].nodePort}")
                .build()

        String output = waitForOutput(
                command,
                "Getting node port for service $serviceName, ns=$namespace",
                "Failed to get node port for service $serviceName, ns=$namespace"
        )

        log.debug("Node port for service $serviceName, ns=$namespace: $output")
        return output
    }

    /**
     * @return A string containing "node/nodeName", e.g. "node/k3d-gitops-playground-server-0"
     */
    String waitForNode() {
        String[] command1 = ['kubectl', 'get', 'node', '-oname']
        String[] command2 = ['head', '-n1']

        String output = waitForOutput(
                command1, command2,
                "Waiting for first node of the cluster to become ready",
                "Failed waiting for node of the cluster to become ready"
        )

        log.debug("First node of the cluster is ready: $output")
        return output
    }

    String applyYaml(String yamlLocation) {
        commandExecutor.execute("kubectl apply -f $yamlLocation").stdOut
    }

    /**
     * Creates a namespace with the specified name if it does not already exist.
     *
     * @param name the name of the namespace to create. Must not be {@code null} or empty.
     *
     * @throws IllegalArgumentException if the {@code name} is {@code null} or empty.
     * @throws RuntimeException if an error occurs during the creation of the namespace,
     *         such as insufficient permissions.
     */
    void createNamespace(String name) {
        validateNamespace(name)

        if (!exists(name)) {

            log.debug("Namespace ${name} does not exist, proceeding to create.")

            // Create the namespace
            String[] createNamespaceCommand = new Kubectl("create", "namespace", name).build()
            try {
                CommandExecutor.Output createNamespaceOutput = commandExecutor.execute(createNamespaceCommand)
                log.debug("Namespace ${name} created successfully.")
            } catch (Exception e) {
                throw new RuntimeException("Failed to create namespace ${name} (possibly due to insufficient permissions)", e)
            }
        }

    }

    private boolean exists(String namespace) {
// Check if the namespace already exists based on exitCode
        String[] checkNamespaceCommand = new Kubectl("get", "namespace", namespace).build()
        CommandExecutor.Output checkNamespaceOutput = commandExecutor.execute(checkNamespaceCommand, false)

        if (checkNamespaceOutput.exitCode == 0) {
            log.debug("Namespace ${namespace} already exists.")
            return true
        }
        return false
    }

    private void validateNamespace(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Namespace name must be provided and cannot be null or empty.")
        }
    }

    /**
     * Creates multiple namespaces based on the given list of namespace names.
     *
     * @param names a list of strings representing the names of the namespaces to be created.
     *              Must not be {@code null}.
     *
     * @throws IllegalArgumentException if the {@code names} list is {@code null}.
     */
    void createNamespaces(List<String> names) {
        if (names == null) {
            throw new IllegalArgumentException("Namespaces must be provided and cannot be null.")
        }
        names.each { name ->
            createNamespace(name)
        }
    }

    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createSecret(String type, String name, String namespace = '', Tuple2... literals) {
        def command1 = kubectl('create', 'secret', type, name)
                .namespace(namespace)
                .mandatory('--from-literal', literals)
                .dryRunOutputYaml()
                .build()

        commandExecutor.execute(command1, APPLY_FROM_STDIN)
    }

    String getArgoCDNamespacesSecret(String name, String namespace = '') {
        String[] command = ["kubectl", "get", 'secret', name, "-n", "${namespace}", '-ojsonpath={.data.namespaces}']
        String output = waitForOutput(
                command,
                "Getting Secret from Cluster",
                "Failed getting Secret from Cluster"
        )
        return output
    }


    /**
     * Idempotent create, i.e. overwrites if exists.
     */
    void createImagePullSecret(String name, String namespace = '', String host, String user, String password) {
        def command1 = kubectl('create', 'secret', 'docker-registry', name)
                .namespace(namespace)
                .mandatory('--docker-server', host)
                .mandatory('--docker-username', user)
                .mandatory('--docker-password', password)
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

    void labelRemove(String resource, String name, String namespace = '', String... keys) {
        Tuple2[] tuples = keys.collect { new Tuple2("${it}-", "") }.toArray(new Tuple2[0])
        label(resource, name, namespace, tuples)
    }

    void label(String resource, String name, String namespace = '', Tuple2... keyValues) {
        if (!keyValues) {
            throw new RuntimeException("Missing key-value-pairs")
        }
        String command =
                "kubectl label ${resource} ${name}${namespace ? " -n ${namespace}" : ''} " +
                        '--overwrite ' + // Make idempotent
                        keyValues.collect { "${it.v1}${it.v2 ? "=${it.v2}" : ''}" }.join(' ')
        commandExecutor.execute(command)
    }

    String run(String name, String image, String namespace = '', Map overrides = [:], String... params) {

        def command1 = kubectl('run', name)
                .mandatory('--image', image)
                .namespace(namespace)
                .optional(params)
                .optional('--overrides', mapToJson(overrides, 'kubectl run overrides'))
                .build()

        commandExecutor.execute(command1).stdOut
    }

    void patch(String resource, String name, String namespace = '', String type = '', Map yaml) {
        // We're using a patch file here, instead of a patch JSON (--patch), because of quoting issues
        // ERROR c.c.gitops.utils.CommandExecutor - Stderr: error: unable to parse "'{\"stringData\":": yaml: found unexpected end of stream
        File patchYaml = File.createTempFile('gitops-playground-patch-yaml', '')
        log.trace("Writing patch YAML: ${yaml}")
        fileSystemUtils.writeYaml(yaml, patchYaml)

        //  kubectl patch secret argocd-secret -p '{"stringData": { "admin.password": "'"${bcryptArgoCDPassword}"'"}}' || true
        String command =
                "kubectl patch ${resource} ${name}${namespace ? " -n ${namespace}" : ''}" +
                        (type ? " --type=$type" : '') +
                        " --patch-file=${patchYaml.absolutePath}"
        commandExecutor.execute(command)
    }

    void delete(String resource, String namespace = '', Tuple2... selectors) {
        if (!selectors) {
            throw new RuntimeException("Missing selectors")
        }
        // kubectl delete secret -n argocd -l owner=helm,name=argocd
        String command =
                "kubectl delete ${resource}${namespace ? " -n ${namespace}" : ''}" +
                        ' --ignore-not-found=true ' + // Make idempotent
                        selectors.collect { "--selector=${it.v1}=${it.v2}" }.join(' ')

        commandExecutor.execute(command)
    }

    void delete(String resource, String namespace, String name) {
        String command =
                "kubectl delete ${resource}${namespace ? " -n ${namespace}" : ''}" +
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

            def prefix = configProvider.get().application.namePrefix
            assert (parts[0].startsWith(prefix))

            return new CustomResource(parts[0].substring(prefix.length()), parts[1])
        }
    }

    String getConfigMap(String mapName, String key) {
        String[] command = ["kubectl", "get", "configmap", mapName, "-o", "jsonpath={.data['" + key.replace(".", "\\.") + "']}"]
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

    /**
     * @param resource resource to get the annotation from
     * @param name name of the resource, only one resource allowed!
     * @param key key of the annotation
     * @param namespace namespace of the resource (if not cluster wide)
     *
     * @return the value of the annotation
     */
    String getAnnotation(String resource, String name, String key, String namespace = '') {
        List<String> commandAsList = [
                "kubectl",
                "get",
                resource,
                name,
                "-o",
                // jsonpath expects a single resource object
                // some requests with multiple resources may result in a listed response
                // that does not match the jsonpath
                "jsonpath={.metadata.annotations}"
        ]
        if (namespace) {
            commandAsList.add("-n $namespace" as String)
        }
        String[] command = commandAsList.toArray(new String[0])
        def result = commandExecutor.execute(command, false)
        if (!result.getStdErr().isEmpty()) {
            throw new RuntimeException("Failed to fetch data from resource [$resource/$name] in namespace [$namespace]: ${result.stdErr}")
        }
        log.debug("getAnnotation returns = ${result.stdOut}")
        def value = new JsonSlurper().parseText(result.stdOut) as Map
        String myResult = value[key]
        return myResult
    }


    private Kubectl kubectl(String... args) {
        new Kubectl(args)
    }

    /**
     * Patches the nodePort of a specified port in a service.
     *
     * @param serviceName The name of the service to patch.
     * @param namespace The namespace of the service.
     * @param portName The name of the port to patch.
     * @param newNodePort The new nodePort value to set.
     *
     * @throws IllegalArgumentException if name, namespace, portName, and nodePort are invalid.
     * @throws RuntimeException if an error occurs while patching the service (i.e. portName not found).
     */
    void patchServiceNodePort(String serviceName, String namespace, String portName, int newNodePort) {
        validateInputForPatch(serviceName, namespace, portName, newNodePort)

        // Get the current service spec to find the index of the port to patch
        String[] getServiceCommand = new Kubectl("get", "service", serviceName)
                .namespace(namespace)
                .mandatory("-o", "json")
                .build()
        CommandExecutor.Output getServiceOutput = commandExecutor.execute(getServiceCommand)
        def serviceSpec = new JsonSlurper().parseText(getServiceOutput.stdOut)
        def ports = serviceSpec['spec']['ports']

        // Find the index of the port to patch
        def portIndex = ports.findIndexOf { it['name'] == portName }
        if (portIndex == -1) {
            throw new RuntimeException("Port with name ${portName} not found in service ${serviceName}.")
        }

        // Create the JSON patch for the specific port
        def patch = [
                [
                        op   : "replace",
                        path : "/spec/ports/${portIndex}/nodePort",
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

    private static String mapToJson(Map kubectlJson, String debugPrefix) {
        if (kubectlJson.isEmpty()) {
            return ''
        }

        JsonBuilder json = new JsonBuilder(kubectlJson)
        log.debug("${debugPrefix} JSON pretty printed:\n${json.toPrettyString()}")
        // Note that toPrettyString() will lead to empty results in some shell, e.g. plain sh 🧐 
        return json.toString()
    }

    private void validateInputForPatch(String serviceName, String namespace, String portName, int newNodePort) {
        if (!serviceName || !namespace || !portName || newNodePort <= 0) {
            throw new IllegalArgumentException("Service name, namespace, port name, and valid nodePort must be provided")
        }
    }

    /**
     * Waits until the specified resource reaches the desired phase.
     *
     * @param resourceType The type of the Kubernetes resource (e.g., pod, deployment).
     * @param resourceName The name of the specific resource.
     * @param namespace The namespace of the resource.
     * @param desiredPhase The desired phase to wait for (e.g., Running, Succeeded).
     * @param timeoutSeconds The maximum time to wait for the desired phase in seconds.
     * @param checkIntervalSeconds The interval between status checks in seconds.
     *
     * @throws IllegalArgumentException if Resource type, name, namespace, desired phase, Timeout and check interval are invalid.
     * @throws RuntimeException if the desired phase is not reached within the timeout period.
     */
    void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase, int timeoutSeconds, int checkIntervalSeconds) {
        validateInputForWaitPhase(resourceType, resourceName, namespace, desiredPhase, timeoutSeconds, checkIntervalSeconds)

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

        // Never reached the desired Phase, so throw a RuntimeException and end the execution
        throw new RuntimeException("Timeout reached. Resource ${resourceType}/${resourceName} in namespace ${namespace} did not reach the desired phase: ${desiredPhase} within ${timeoutSeconds} seconds.")
    }

    private void validateInputForWaitPhase(String resourceType, String resourceName, String namespace, String desiredPhase, int timeoutSeconds, int checkIntervalSeconds) {
        if (!resourceType || !resourceName || !namespace || !desiredPhase) {
            throw new IllegalArgumentException("Resource type, name, namespace, and desired phase must be provided")
        }
        if (timeoutSeconds <= 0 || checkIntervalSeconds <= 0) {
            throw new IllegalArgumentException("Timeout and check interval must be greater than zero")
        }
    }

    /**
     * Waits for a specific resource to reach the desired phase with default timeout and interval.
     *
     * @param resourceType The type of the Kubernetes resource (e.g., pod, deployment).
     * @param resourceName The name of the specific resource.
     * @param namespace The namespace of the resource.
     * @param desiredPhase The desired phase to wait for (e.g., Running, Succeeded).
     *
     * @see #waitForResourcePhase(String, String, String, String, int, int)
     */
    void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase) {
        waitForResourcePhase(resourceType, resourceName, namespace, desiredPhase, 60, 1)
    }

    @Immutable
    static class CustomResource {
        String namespace
        String name
    }

    private class Kubectl {
        private List<String> command = ['kubectl']

        Kubectl(String... args) {
            command.addAll(args)
        }

        Kubectl namespace(String namespace) {
            if (namespace) {
                this.command += ['-n', namespace]
            }
            return this
        }

        Kubectl mandatory(String paramName, String value) {
            // Here we could assert that value != null. For historical reasons we don't, for now.
            this.command += [paramName, value]
            return this
        }

        Kubectl mandatory(String paramName, Tuple2... values) {
            if (!values) {
                throw new RuntimeException("Missing values for parameter '${paramName}' in command '${command.join(' ')}'")
            }
            values.each { command += [paramName, "${it.v1}=${it.v2 ? it.v2 : ''}".toString()] }
            return this
        }

        Kubectl optional(String paramName, String value) {
            if (value) {
                this.command += [paramName, value]
            }
            return this
        }

        Kubectl optional(String... params) {
            command.addAll(params)
            return this
        }

        Kubectl dryRunOutputYaml() {
            this.command += ['--dry-run=client', '-oyaml']
            return this
        }

        String[] build() {
            this.command
        }
    }

}