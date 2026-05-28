package com.cloudogu.gitops.infrastructure.kubernetes.api
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext

import com.cloudogu.gitops.config.Credentials
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import jakarta.inject.Singleton
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import groovy.io.FileType
/**
 * Kubernetes client using Fabric8 Kubernetes Client.*/
@Slf4j
@Singleton
class K8sClient {

	// ========================================
	// Constants
	// ========================================

	private static final String DEFAULT_NAMESPACE = "default"
	private static final String INTERNAL_IP_TYPE = "InternalIP"
	private static final String DOCKER_CONFIG_JSON_TYPE = "kubernetes.io/dockerconfigjson"
	private static final String DOCKER_CONFIG_JSON_KEY = ".dockerconfigjson"

	private static final int DEFAULT_TIMEOUT_SECONDS = 60
	private static final int DEFAULT_CHECK_INTERVAL_SECONDS = 1
	private static final int FABRIC8_REQUEST_TIMEOUT_MILLIS = 60_000
	private static final int FABRIC8_CONNECTION_TIMEOUT_MILLIS = 10_000

	// ========================================
	// Instance Variables
	// ========================================

	protected int SLEEPTIME = 1000
	protected int DEFAULT_RETRIES = 120

	KubernetesClient client

	K8sClient() {
		Config config = new ConfigBuilder()
				.withRequestTimeout(FABRIC8_REQUEST_TIMEOUT_MILLIS)
				.withConnectionTimeout(FABRIC8_CONNECTION_TIMEOUT_MILLIS)
				.build()

		this.client = new KubernetesClientBuilder()
				.withConfig(config)
				.build()

	}

	// ========================================
	// Public API Methods - Node Operations
	// ========================================

	/**
	 * Waits for the first node in the cluster to become available.
	 *
	 * @return The name of the first available node (e.g., "k3d-gitops-playground-server-0")
	 * @throws RuntimeException if no node becomes available within the retry limit
	 */
	String waitForNode() {
		log.debug("Waiting for first node of the cluster to become ready")

		String nodeName = waitForResourceWithRetry("node") { ->
			NodeList nodes = client.nodes().list()
			if (nodes?.items && !nodes.items.isEmpty()) {
				return nodes.items[0].metadata.name
			}
			return null
		}

		log.debug("First node of the cluster is ready: $nodeName")
		return nodeName
	}

	/**
	 * Waits for and retrieves the internal IP address of the first node.
	 * For k3d, this is either the host's IP or the k3d API server's container IP.
	 *
	 * @return The internal IP address of the node (IPv4)
	 * @throws RuntimeException if the internal IP cannot be retrieved
	 */
	String waitForInternalNodeIp() {
		String nodeName = waitForNode()
		log.debug("Waiting for internal IP of node $nodeName")

		String internalIp = waitForResourceWithRetry("internal IP of node $nodeName") { ->
			Node node = client.nodes().withName(nodeName).get()
			if (node?.status?.addresses) {
				def internalIpAddress = node.status.addresses.find { it.type == INTERNAL_IP_TYPE }
				return internalIpAddress?.address
			}
			return null
		}

		log.debug("Internal IP of node $nodeName: $internalIp")
		return internalIp
	}

	// ========================================
	// Public API Methods - Service Operations
	// ========================================

	/**
	 * Waits for a service's NodePort to become available.
	 *
	 * @param serviceName The name of the service
	 * @param namespace The namespace of the service
	 * @return The NodePort as a string
	 * @throws RuntimeException if the NodePort cannot be retrieved
	 */
	String waitForNodePort(String serviceName, String namespace) {
		log.debug("Getting node port for service $serviceName, ns=$namespace")

		String nodePort = waitForResourceWithRetry("node port for service $serviceName") { ->
			Service service = client.services().inNamespace(namespace).withName(serviceName).get()
			if (service?.spec?.ports && !service.spec.ports.isEmpty()) {
				Integer port = service.spec.ports[0].nodePort
				return port?.toString()
			}
			return null
		}

		log.debug("Node port for service $serviceName, ns=$namespace: $nodePort")
		return nodePort
	}

	/**
	 * Creates a NodePort service (idempotent).
	 *
	 * @param name The name of the service
	 * @param tcp Port pairs specified as '<port>:<targetPort>'
	 * @param nodePort The NodePort (optional)
	 * @param namespace The namespace (defaults to "default")
	 */
	void createServiceNodePort(String name, String tcp, String nodePort = '', String namespace = '') {
		log.debug("Creating NodePort service $name in namespace $namespace")

		def ports = tcp.split(':')
		int port = Integer.parseInt(ports[0])
		int targetPort = ports.size() > 1 ? Integer.parseInt(ports[1]) : port

		def portBuilder = new ServiceBuilder()
			.withNewMetadata()
			.withName(name)
			.withNamespace(resolveNamespace(namespace))
			.endMetadata()
			.withNewSpec()
			.withType("NodePort")
			.addNewPort()
			.withPort(port)
			.withTargetPort(new IntOrString(targetPort))

		if (nodePort) {
			portBuilder = portBuilder.withNodePort(Integer.parseInt(nodePort))
		}

		Service service = portBuilder
			.endPort()
			.endSpec()
			.build()

		executeWithErrorHandling("create NodePort service $name") {
			client.services()
				.inNamespace(resolveNamespace(namespace))
				.resource(service)
				.createOrReplace()
		}

		log.debug("NodePort service $name created/updated successfully")
	}

	/**
	 * Patches the nodePort of a specific port in a service.
	 *
	 * @param serviceName The name of the service to patch
	 * @param namespace The namespace of the service
	 * @param portName The name of the port to patch
	 * @param newNodePort The new nodePort value to set
	 * @throws IllegalArgumentException if parameters are invalid
	 * @throws RuntimeException if the port is not found or patching fails
	 */
	void patchServiceNodePort(String serviceName, String namespace, String portName, int newNodePort) {
		validateServiceNodePortPatch(serviceName, namespace, portName, newNodePort)

		log.debug("Patching service $serviceName port $portName with nodePort $newNodePort")

		Service service = client.services().inNamespace(namespace).withName(serviceName).get()

		if (!service) {
			throw new RuntimeException("Service ${serviceName} not found in namespace ${namespace}")
		}

		def ports = service.spec.ports
		def portIndex = ports.findIndexOf { it.name == portName }

		if (portIndex == -1) {
			throw new RuntimeException("Port with name ${portName} not found in service ${serviceName}.")
		}

		// Create JSON patch
		def patch = [[op   : "replace",
		              path : "/spec/ports/${portIndex}/nodePort",
		              value: newNodePort]]

		String patchJson = new JsonBuilder(patch).toString()
		PatchContext patchContext = new PatchContext.Builder()
			.withPatchType(PatchType.JSON)
			.build()

		executeWithErrorHandling("patch service $serviceName") {
			client.services()
				.inNamespace(namespace)
				.withName(serviceName)
				.patch(patchContext, patchJson)
		}

		log.debug("Service ${serviceName} in namespace ${namespace} successfully patched with nodePort ${newNodePort} for port ${portName}.")
	}

	// ========================================
	// Public API Methods - Namespace Operations
	// ========================================

	/**
	 * Creates a namespace if it does not already exist (idempotent).
	 *
	 * @param name The name of the namespace to create
	 * @throws IllegalArgumentException if name is null or empty
	 * @throws RuntimeException if creation fails
	 */
	void createNamespace(String name) {
		validateNamespaceName(name)

		if (!namespaceExists(name)) {
			log.debug("Namespace ${name} does not exist, proceeding to create.")

			Namespace namespace = new NamespaceBuilder()
				.withNewMetadata()
				.withName(name)
				.endMetadata()
				.build()

			executeWithErrorHandling("create namespace ${name}") {
				client.namespaces().resource(namespace).create()
			}

			log.debug("Namespace ${name} created successfully.")
		}
	}

	/**
	 * Creates multiple namespaces.
	 *
	 * @param names List of namespace names to create
	 * @throws IllegalArgumentException if names is null
	 */
	void createNamespaces(List<String> names) {
		if (names == null) {
			throw new IllegalArgumentException("Namespaces must be provided and cannot be null.")
		}
		names.each { name -> createNamespace(name) }
	}

	/**
	 * Checks if a namespace exists.
	 *
	 * @param namespace The namespace name
	 * @return true if the namespace exists, false otherwise
	 */
	boolean namespaceExists(String namespace) {
		try {
			Namespace ns = client.namespaces().withName(namespace).get()
			if (ns != null) {
				log.debug("Namespace ${namespace} already exists.")
				return true
			}
		} catch (Exception e) {
			log.trace("Namespace ${namespace} does not exist: ${e.message}")
		}
		return false
	}

	// ========================================
	// Public API Methods - Secret Operations
	// ========================================

	/**
	 * Creates or updates a generic secret (idempotent).
	 *
	 * @param type The type of secret
	 * @param name The name of the secret
	 * @param namespace The namespace (defaults to "default")
	 * @param literals Key-value pairs as Tuple2
	 */
	void createSecret(String type, String name, String namespace = '', Tuple2... literals) {
		log.debug("Creating secret $name of type $type in namespace $namespace")

		Map<String, String> data = [:]
		literals.each { tuple -> data[tuple.v1 as String] = tuple.v2 as String
		}

		String resolvedType = type == 'generic' ? 'Opaque' : type
		Secret secret = new SecretBuilder()
			.withNewMetadata()
			.withName(name)
			.withNamespace(resolveNamespace(namespace))
			.endMetadata()
			.withType(resolvedType)
			.withStringData(data)
			.build()

		executeWithErrorHandling("create secret $name") {
			def secretsClient = client.secrets().inNamespace(resolveNamespace(namespace))
			if (secretsClient.withName(name).get()) {
				secretsClient.withName(name).delete()
			}
			secretsClient.resource(secret).create()
		}

		log.debug("Secret $name created/updated successfully")
	}

	/**
	 * Creates or updates an image pull secret (idempotent).
	 *
	 * @param name The name of the secret
	 * @param namespace The namespace (defaults to "default")
	 * @param host The Docker registry host
	 * @param user The username
	 * @param password The password
	 */
	void createImagePullSecret(String name, String namespace = '', String host, String user, String password) {
		log.debug("Creating image pull secret $name in namespace $namespace")

		String auth = Base64.encoder.encodeToString("${user}:${password}".bytes)
		String dockerConfig = """{"auths":{"${host}":{"username":"${user}","password":"${password}","auth":"${auth}"}}}"""

		Secret secret = new SecretBuilder()
			.withNewMetadata()
			.withName(name)
			.withNamespace(resolveNamespace(namespace))
			.endMetadata()
			.withType(DOCKER_CONFIG_JSON_TYPE)
			.addToStringData(DOCKER_CONFIG_JSON_KEY, dockerConfig)
			.build()

		executeWithErrorHandling("create image pull secret $name") {
			client.secrets()
				.inNamespace(resolveNamespace(namespace))
				.resource(secret)
				.createOrReplace()
		}

		log.debug("Image pull secret $name created/updated successfully")
	}

	/**
	 * Retrieves the 'namespaces' data from an ArgoCD secret.
	 *
	 * @param name The name of the secret
	 * @param namespace The namespace (defaults to "default")
	 * @return The base64-encoded namespaces data
	 * @throws RuntimeException if the secret or data cannot be retrieved
	 */
	String getArgoCDNamespacesSecret(String name, String namespace = '') {
		log.debug("Getting Secret $name from namespace $namespace")

		String secretData = waitForResourceWithRetry("secret $name") { ->
			Secret secret = client.secrets()
				.inNamespace(resolveNamespace(namespace))
				.withName(name)
				.get()

			return secret?.data?.containsKey('namespaces') ? secret.data['namespaces'] : null
		}

		return secretData
	}

	/**
	 * Extracts credentials from a Kubernetes secret.
	 *
	 * @param secretname The name of the secret
	 * @param namespace The namespace
	 * @param usernameKey The key for username (defaults to 'username')
	 * @param passwordKey The key for password (defaults to 'password')
	 * @return Credentials object containing username and password
	 * @throws RuntimeException if the secret cannot be parsed
	 */
	Credentials getCredentialsFromSecret(String secretname, String namespace, String usernameKey = 'username', String passwordKey = 'password') {
		executeWithErrorHandling("get credentials from secret ${secretname}") {
			Secret secret = client.secrets()
				.inNamespace(namespace)
				.withName(secretname)
				.get()

			def secretData = secret.getData()
			String username = new String(Base64.getDecoder().decode(secretData[usernameKey]))
			String password = new String(Base64.getDecoder().decode(secretData[passwordKey]))
			return new Credentials(username, password)
		}
	}

	/**
	 * Extracts credentials from a Kubernetes secret using a Credentials object as input.
	 *
	 * @param credentials Credentials object with secret location information
	 * @return Updated Credentials object with username and password
	 * @throws RuntimeException if the secret cannot be parsed
	 */
	Credentials getCredentialsFromSecret(Credentials credentials) {
		executeWithErrorHandling("get credentials from secret ${credentials.secretName}") {
			Secret secret = client.secrets()
				.inNamespace(credentials.secretNamespace)
				.withName(credentials.secretName)
				.get()

			def secretData = secret.getData()
			def usernameEncoded = secretData[credentials.usernameKey]
			String username = usernameEncoded != null ? new String(Base64.decoder.decode(usernameEncoded)) : credentials.username
			String password = new String(Base64.getDecoder().decode(secretData[credentials.passwordKey]))

			Credentials credentialsNew = new Credentials(credentials)
			credentialsNew.username = username
			credentialsNew.password = password

			return credentialsNew
		}
	}

	// ========================================
	// Public API Methods - ConfigMap Operations
	// ========================================

	/**
	 * Creates or updates a ConfigMap from a file (idempotent).
	 *
	 * @param name The name of the ConfigMap
	 * @param namespace The namespace (defaults to "default")
	 * @param filePath The path to the file
	 * @throws RuntimeException if the file is not found
	 */
	void createConfigMapFromFile(String name, String namespace = '', String filePath) {
		log.debug("Creating ConfigMap $name from file $filePath in namespace $namespace")

		File file = new File(filePath)
		if (!file.exists()) {
			throw new RuntimeException("File not found: $filePath")
		}

		Map<String, String> data = [(file.name): file.text]

		ConfigMap configMap = new ConfigMapBuilder()
			.withNewMetadata()
			.withName(name)
			.withNamespace(resolveNamespace(namespace))
			.endMetadata()
			.withData(data)
			.build()

		executeWithErrorHandling("create ConfigMap $name from file") {
			client.configMaps()
				.inNamespace(resolveNamespace(namespace))
				.resource(configMap)
				.createOrReplace()
		}

		log.debug("ConfigMap $name created/updated successfully")
	}

	/**
	 * Retrieves a value from a ConfigMap.
	 *
	 * @param mapName The name of the ConfigMap
	 * @param key The key to retrieve
	 * @return The value associated with the key
	 * @throws RuntimeException if the ConfigMap or key is not found
	 */
	String getConfigMap(String mapName, String key) {
		log.debug("Getting ConfigMap $mapName, key: $key")

		ConfigMap configMap = client.configMaps().inNamespace(DEFAULT_NAMESPACE).withName(mapName).get()

		if (!configMap) {
			throw new RuntimeException("Could not fetch configmap $mapName")
		}

		if (!configMap.data?.containsKey(key)) {
			throw new RuntimeException("Could not fetch $key within config-map $mapName")
		}

		return configMap.data[key]
	}

	// ========================================
	// Public API Methods - Resource Management
	// ========================================

	/**
	 * Applies YAML resources from a file.
	 *
	 * @param yamlLocation The path to the YAML file
	 * @return A success message
	 * @throws RuntimeException if the file is not found or application fails
	 */
	String applyYaml(String yamlLocation) {
		log.debug("Applying YAML from $yamlLocation")

		if (yamlLocation.startsWith("http://") || yamlLocation.startsWith("https://")) {
			int appliedResources = applyYamlStream(new URL(yamlLocation).openStream(), yamlLocation)
			return "Applied ${appliedResources} resource(s) from $yamlLocation"
		}

		File location = new File(yamlLocation)

		if (!location.exists()) {
			throw new RuntimeException("File or directory not found: $yamlLocation")
		}

		if (location.isDirectory()) {
			List<File> yamlFiles = []
			location.traverse(type: FileType.FILES) { File file ->
				if (file.name.endsWith(".yaml") || file.name.endsWith(".yml")) {
					yamlFiles.add(file)
				}
			}

			yamlFiles = yamlFiles.sort { it.absolutePath }

			int appliedResources = 0
			yamlFiles.each { File file ->
				appliedResources += applyYamlStream(file.newInputStream(), file.absolutePath)
			}

			return "Applied ${appliedResources} resource(s) from directory $yamlLocation"
		}

		int appliedResources = applyYamlStream(location.newInputStream(), yamlLocation)
		return "Applied ${appliedResources} resource(s) from $yamlLocation"
	}

	private int applyYamlStream(InputStream stream, String sourceDescription) {
		def resources = executeWithErrorHandling("load YAML from $sourceDescription") {
			try {
				return client.load(stream).items()
			} finally {
				stream.close()
			}
		}

		resources.each { resource ->
			executeWithErrorHandling("apply resource from $sourceDescription") {
				def resourceClient = client.resource(resource)

				if (resource.metadata?.namespace) {
					resourceClient = resourceClient.inNamespace(resource.metadata.namespace)
				}

				resourceClient.createOrReplace()
			}
		}

		return resources.size()
	}

	/**
	 * Adds or updates labels on a resource.
	 *
	 * @param resource The resource type (e.g., "pod", "service")
	 * @param name The name of the resource
	 * @param namespace The namespace (defaults to "default")
	 * @param keyValues Label key-value pairs as Tuple2. Keys ending with '-' will be removed.
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	void label(String resource, String name, String namespace = '', Tuple2... keyValues) {
		if (!keyValues) {
			throw new RuntimeException("Missing key-value-pairs")
		}

		if (name == '--all') {
			client.nodes().list().items.each { node -> label(resource, node.metadata.name, namespace, keyValues)
			}
			return
		}

		log.debug("Labeling $resource/$name in namespace $namespace")

		Map<String, String> labelsToAdd = [:]
		List<String> labelsToRemove = []

		keyValues.each { tuple ->
			String key = tuple.v1 as String
			String value = tuple.v2 as String

			if (key.endsWith('-')) {
				labelsToRemove.add(key.substring(0, key.length() - 1))
			} else {
				labelsToAdd[key] = value
			}
		}

		executeWithErrorHandling("label $resource/$name") {
			def resourceClient = getResourceClient(resource, name, namespace)
			HasMetadata existingResource = resourceClient.get() as HasMetadata

			if (!existingResource) {
				throw new RuntimeException("Resource $resource/$name not found")
			}

			def existingLabels = existingResource.metadata?.labels ?: [:]
			labelsToRemove.each { key -> existingLabels.remove(key) }
			existingLabels.putAll(labelsToAdd)

			existingResource.metadata.labels = existingLabels
			resourceClient.replace(existingResource)
		}

		log.debug("Labels updated successfully")
	}

	/**
	 * Removes labels from a resource.
	 *
	 * @param resource The resource type
	 * @param name The name of the resource
	 * @param namespace The namespace (defaults to "default")
	 * @param keys The label keys to remove
	 */
	void labelRemove(String resource, String name, String namespace = '', String... keys) {
		Tuple2[] tuples = keys.collect { new Tuple2("${it}-", "") }.toArray(new Tuple2[0])
		label(resource, name, namespace, tuples)
	}

	/**
	 * Patches a Kubernetes resource.
	 *
	 * @param resource The resource type
	 * @param name The name of the resource
	 * @param namespace The namespace (defaults to "default")
	 * @param type The patch type ('merge', 'strategic', 'json')
	 * @param yaml The patch content as a Map
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	void patch(String resource, String name, String namespace = '', String type = '', Map yaml) {
		log.debug("Patching $resource/$name in namespace $namespace")

		PatchContext patchContext = createPatchContext(type)
		String patchJson = new JsonBuilder(yaml).toString()
		log.trace("Patch JSON: $patchJson")

		executeWithErrorHandling("patch $resource/$name") {
			def resourceClient = getResourceClient(resource, name, namespace)
			resourceClient.patch(patchContext, patchJson)
		}

		log.debug("Resource $resource/$name patched successfully")
	}

	/**
	 * Deletes resources by label selector.
	 *
	 * @param resource The resource type
	 * @param namespace The namespace (defaults to "default")
	 * @param selectors Label selectors as Tuple2
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	void delete(String resource, String namespace = '', Tuple2... selectors) {
		if (!selectors) {
			throw new RuntimeException("Missing selectors")
		}

		log.debug("Deleting $resource in namespace $namespace with selectors")

		Map<String, String> labels = [:]
		selectors.each { tuple -> labels[tuple.v1 as String] = tuple.v2 as String
		}

		try {
			deleteResourcesByType(resource, resolveNamespace(namespace), labels)
			log.debug("Resources deleted successfully")
		} catch (Exception e) {
			log.warn("Failed to delete resources (may not exist): ${e.message}")
		}
	}

	/**
	 * Deletes a specific resource by name.
	 *
	 * @param resource The resource type
	 * @param namespace The namespace
	 * @param name The name of the resource
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	void delete(String resource, String namespace, String name) {
		log.debug("Deleting $resource/$name in namespace $namespace")

		try {
			def resourceClient = getResourceClient(resource, name, namespace)
			resourceClient.delete()
			log.debug("Resource $resource/$name deleted successfully")
		} catch (Exception e) {
			log.warn("Failed to delete resource (may not exist): ${e.message}")
		}
	}

	/**
	 * Runs a pod with the specified image.
	 *
	 * @param name The name of the pod
	 * @param image The container image
	 * @param namespace The namespace (defaults to "default")
	 * @param overrides Additional pod spec overrides (not yet fully implemented)
	 * @param params Additional parameters
	 * @return A message indicating the pod was created
	 */
	String run(String name, String image, String namespace = '', Map overrides = [:], String... params) {
		log.debug("Running pod $name with image $image in namespace $namespace")

		Pod pod = new PodBuilder()
			.withNewMetadata()
			.withName(name)
			.withNamespace(resolveNamespace(namespace))
			.endMetadata()
			.withNewSpec()
			.addNewContainer()
			.withName(name)
			.withImage(image)
			.endContainer()
			.endSpec()
			.build()

		if (overrides) {
			log.debug("Applying overrides: $overrides")
			// TODO: Implement deep merge of overrides
		}

		Pod createdPod = executeWithErrorHandling("run pod $name") {
			client.pods()
				.inNamespace(resolveNamespace(namespace))
				.resource(pod)
				.create()
		}

		log.debug("Pod $name created successfully")
		return "pod/${createdPod.metadata.name} created"
	}

	// ========================================
	// Public API Methods - Query Operations
	// ========================================

	/**
	 * Retrieves custom resources of a specific type across all namespaces.
	 *
	 * @param resource The custom resource type
	 * @return List of CustomResource objects
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	List<CustomResource> getCustomResource(String resource) {
		log.debug("Getting custom resources of type $resource")

		try {
			def apiClient = client.genericKubernetesResources(resource)
			def resourceList = apiClient.inAnyNamespace().list()

			if (!resourceList || !(resourceList.hasProperty('items')) || !resourceList.items) {
				return []
			}

			def items = resourceList.items as List
			return items.collect { item ->
				def itemMap = item as Map
				def metadata = itemMap.get('metadata') as Map
				new CustomResource((metadata?.get('namespace') ?: '') as String,
					(metadata?.get('name') ?: '') as String)
			}
		} catch (Exception e) {
			log.warn("Failed to get custom resources: ${e.message}")
			return []
		}
	}

	/**
	 * Retrieves the value of an annotation from a resource.
	 *
	 * @param resource The resource type
	 * @param name The name of the resource
	 * @param key The annotation key
	 * @param namespace The namespace (defaults to "default")
	 * @return The annotation value
	 * @throws RuntimeException if the resource or annotation is not found
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	String getAnnotation(String resource, String name, String key, String namespace = '') {
		log.debug("Getting annotation $key from $resource/$name in namespace $namespace")

		def resourceClient = getResourceClient(resource, name, namespace)
		def resourceObj = resourceClient.get()
		HasMetadata k8sResource = resourceObj as HasMetadata

		if (!k8sResource) {
			throw new RuntimeException("Resource $resource/$name not found")
		}

		def annotations = k8sResource.metadata?.annotations
		if (!annotations) {
			throw new RuntimeException("No annotations found on resource $resource/$name")
		}

		String value = annotations[key]
		log.debug("getAnnotation returns = ${value}")
		return value
	}

	/**
	 * Retrieves the current Kubernetes context.
	 *
	 * @return The name of the current context, or "(current context not set)"
	 */
	String getCurrentContext() {
		try {
			String context = client.getConfiguration().getCurrentContext()?.getName()
			return context ?: '(current context not set)'
		} catch (Exception e) {
			log.trace("Failed to get current context: ${e.message}")
			return '(current context not set)'
		}
	}

	// ========================================
	// Public API Methods - Wait Operations
	// ========================================

	/**
	 * Waits for a resource to reach a desired phase.
	 *
	 * @param resourceType The resource type (e.g., "pod", "deployment")
	 * @param resourceName The name of the resource
	 * @param namespace The namespace
	 * @param desiredPhase The phase to wait for (e.g., "Running", "Succeeded")
	 * @param timeoutSeconds Maximum wait time in seconds
	 * @param checkIntervalSeconds Interval between checks in seconds
	 * @throws IllegalArgumentException if parameters are invalid
	 * @throws RuntimeException if timeout is reached
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase,
		int timeoutSeconds, int checkIntervalSeconds) {
		validateWaitForResourcePhaseParams(resourceType, resourceName, namespace, desiredPhase, timeoutSeconds, checkIntervalSeconds)

		log.debug("Waiting for $resourceType/$resourceName to reach phase $desiredPhase")

		long startTime = System.currentTimeMillis()
		long endTime = startTime + (timeoutSeconds * 1000)

		while (System.currentTimeMillis() < endTime) {
			try {
				def resourceClient = getResourceClient(resourceType, resourceName, namespace)
				def resourceObj = resourceClient.get()
				HasMetadata resource = resourceObj as HasMetadata

				if (resource) {
					def status = resource.getAdditionalProperties()?.get('status') as Map
					String phase = status?.get('phase') as String
					if (phase == desiredPhase) {
						log.debug("Resource ${resourceType}/${resourceName} in namespace ${namespace} reached the desired phase: ${desiredPhase}")
						return
					}
					log.debug("Current phase: ${phase}. Waiting for phase: ${desiredPhase}...")
				}
			} catch (Exception e) {
				log.trace("Error checking resource phase: ${e.message}")
			}

			sleep(checkIntervalSeconds * 1000)
		}

		throw new RuntimeException("Timeout reached. Resource ${resourceType}/${resourceName} in namespace ${namespace} " + "did not reach the desired phase: ${desiredPhase} within ${timeoutSeconds} seconds.")
	}

	/**
	 * Waits for a resource to reach a desired phase with default timeout and interval.
	 *
	 * @param resourceType The resource type
	 * @param resourceName The name of the resource
	 * @param namespace The namespace
	 * @param desiredPhase The phase to wait for
	 */
	void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase) {
		waitForResourcePhase(resourceType, resourceName, namespace, desiredPhase,
			DEFAULT_TIMEOUT_SECONDS, DEFAULT_CHECK_INTERVAL_SECONDS)
	}

	// ========================================
	// Private Helper Methods - Retry Logic
	// ========================================

	/**
	 * Generic retry logic for waiting on resources.
	 *
	 * @param resourceDescription Description of the resource being waited on
	 * @param fetchClosure Closure that attempts to fetch the resource
	 * @return The result from the fetchClosure
	 * @throws RuntimeException if the resource is not available after retries
	 */
	private <T> T waitForResourceWithRetry(String resourceDescription, Closure<T> fetchClosure) {
		int tryCount = 0
		T result = null

		while (!result && tryCount < DEFAULT_RETRIES) {
			try {
				result = fetchClosure()
			} catch (Exception e) {
				log.trace("Error fetching ${resourceDescription}: ${e.message}")
			}

			if (!result) {
				tryCount++
				log.debug("Still waiting for ${resourceDescription}... (try $tryCount/$DEFAULT_RETRIES)")
				sleep(SLEEPTIME)
			}
		}

		if (!result) {
			throw new RuntimeException("Failed to retrieve ${resourceDescription} after ${DEFAULT_RETRIES} retries")
		}

		return result
	}

	// ========================================
	// Private Helper Methods - Error Handling
	// ========================================

	/**
	 * Executes a closure with consistent error handling.
	 *
	 * @param operation Description of the operation
	 * @param closure The operation to execute
	 * @return The result of the closure
	 * @throws RuntimeException if the operation fails
	 */
	private <T> T executeWithErrorHandling(String operation, Closure<T> closure) {
		try {
			return closure()
		} catch (Exception e) {
			throw new RuntimeException("Failed to ${operation}: ${e.message}", e)
		}
	}

	// ========================================
	// Private Helper Methods - Resource Client
	// ========================================

	/**
	 * Gets a resource client for a specific resource type and name.
	 *
	 * @param resourceType The type of resource
	 * @param name The name of the resource
	 * @param namespace The namespace
	 * @return A resource client
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	private getResourceClient(String resourceType, String name, String namespace) {
		String ns = resolveNamespace(namespace)

		switch (resourceType.toLowerCase()) {
			case 'pod':
			case 'pods':
				return client.pods().inNamespace(ns).withName(name)

			case 'service':
			case 'services':
			case 'svc':
				return client.services().inNamespace(ns).withName(name)

			case 'deployment':
			case 'deployments':
				return client.apps().deployments().inNamespace(ns).withName(name)

			case 'configmap':
			case 'configmaps':
			case 'cm':
				return client.configMaps().inNamespace(ns).withName(name)

			case 'secret':
			case 'secrets':
				return client.secrets().inNamespace(ns).withName(name)

			case 'namespace':
			case 'namespaces':
			case 'ns':
				return client.namespaces().withName(name)

			case 'node':
			case 'nodes':
				return client.nodes().withName(name)

			case 'serviceaccount':
			case 'serviceaccounts':
				return client.serviceAccounts().inNamespace(ns).withName(name)


			default:
				return getCustomResourceClient(resourceType, name, ns)
		}
	}

	@CompileStatic(TypeCheckingMode.SKIP)
	private getCustomResourceClient(String resourceType, String name, String namespace) {
		String normalized = resourceType.toLowerCase()

		def crd = client.apiextensions()
				.v1()
				.customResourceDefinitions()
				.list()
				.items
				.find { crd ->
					crd.spec.names.kind?.equalsIgnoreCase(resourceType) ||
							crd.spec.names.plural?.equalsIgnoreCase(normalized) ||
							crd.spec.names.singular?.equalsIgnoreCase(normalized) ||
							crd.spec.names.shortNames?.any { it.equalsIgnoreCase(normalized) }
				}

		if (!crd) {
			throw new RuntimeException("No CRD found for custom resource type '${resourceType}'")
		}

		def version = crd.spec.versions.find { it.storage && it.served }?.name ?:
				crd.spec.versions.find { it.storage }?.name ?:
						crd.spec.versions.find { it.served }?.name
		log.debug("Using CRD ${crd.metadata.name} with version ${version}, kind=${crd.spec.names.kind}, plural=${crd.spec.names.plural}")

		if (!version) {
			throw new RuntimeException("No served version found for CRD '${crd.metadata.name}'")
		}

		ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
				.withGroup(crd.spec.group)
				.withVersion(version)
				.withKind(crd.spec.names.kind)
				.withPlural(crd.spec.names.plural)
				.withNamespaced(crd.spec.scope == "Namespaced")
				.build()

		def resourceClient = client.genericKubernetesResources(context)

		if (crd.spec.scope == "Namespaced") {
			return resourceClient.inNamespace(namespace).withName(name)
		}

		return resourceClient.withName(name)
	}

	/**
	 * Deletes resources by type and labels.
	 *
	 * @param resource The resource type
	 * @param namespace The namespace
	 * @param labels The label selectors
	 */
	@CompileStatic(TypeCheckingMode.SKIP)
	private void deleteResourcesByType(String resource, String namespace, Map<String, String> labels) {
		switch (resource.toLowerCase()) {
			case 'secret':
			case 'secrets':
				client.secrets().inNamespace(namespace).withLabels(labels).delete()
				break

			case 'pod':
			case 'pods':
				client.pods().inNamespace(namespace).withLabels(labels).delete()
				break

			case 'service':
			case 'services':
			case 'svc':
				client.services().inNamespace(namespace).withLabels(labels).delete()
				break

			case 'deployment':
			case 'deployments':
				client.apps().deployments().inNamespace(namespace).withLabels(labels).delete()
				break

			case 'configmap':
			case 'configmaps':
			case 'cm':
				client.configMaps().inNamespace(namespace).withLabels(labels).delete()
				break

			default:
				client.genericKubernetesResources(resource).inNamespace(namespace).withLabels(labels).delete()
		}
	}

	// ========================================
	// Private Helper Methods - Utilities
	// ========================================

	/**
	 * Resolves a namespace, defaulting to "default" if empty.
	 *
	 * @param namespace The namespace to resolve
	 * @return The resolved namespace
	 */
	private String resolveNamespace(String namespace) {
		return namespace ?: DEFAULT_NAMESPACE
	}

	/**
	 * Creates a PatchContext based on the patch type string.
	 *
	 * @param type The patch type ('merge', 'strategic', 'json', or empty for default)
	 * @return A configured PatchContext
	 */
	private PatchContext createPatchContext(String type) {
		PatchType patchType

		if (!type) {
			patchType = PatchType.STRATEGIC_MERGE
		} else {
			switch (type.toLowerCase()) {
				case 'merge':
				case 'strategic':
					patchType = PatchType.STRATEGIC_MERGE
					break
				case 'json':
					patchType = PatchType.JSON
					break
				default:
					patchType = PatchType.STRATEGIC_MERGE
			}
		}

		return new PatchContext.Builder().withPatchType(patchType).build()
	}

	// ========================================
	// Private Helper Methods - Validation
	// ========================================

	/**
	 * Validates a namespace name.
	 *
	 * @param name The namespace name
	 * @throws IllegalArgumentException if the name is invalid
	 */
	private void validateNamespaceName(String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Namespace name must be provided and cannot be null or empty.")
		}
	}

	/**
	 * Validates parameters for service NodePort patching.
	 *
	 * @throws IllegalArgumentException if any parameter is invalid
	 */
	private void validateServiceNodePortPatch(String serviceName, String namespace, String portName, int newNodePort) {
		if (!serviceName || !namespace || !portName || newNodePort <= 0) {
			throw new IllegalArgumentException("Service name, namespace, port name, and valid nodePort must be provided")
		}
	}

	/**
	 * Validates parameters for waitForResourcePhase.
	 *
	 * @throws IllegalArgumentException if any parameter is invalid
	 */
	private void validateWaitForResourcePhaseParams(String resourceType, String resourceName, String namespace,
		String desiredPhase, int timeoutSeconds, int checkIntervalSeconds) {
		if (!resourceType || !resourceName || !namespace || !desiredPhase) {
			throw new IllegalArgumentException("Resource type, name, namespace, and desired phase must be provided")
		}
		if (timeoutSeconds <= 0 || checkIntervalSeconds <= 0) {
			throw new IllegalArgumentException("Timeout and check interval must be greater than zero")
		}
	}

	/**
	 * Return current namespace from running pod.
	 * @return
	 */
	String getCurrentNamespace() {
		return this.client.getNamespace()
	}

	// ========================================
	// Inner Classes
	// ========================================

	/**
	 * Represents a custom Kubernetes resource with namespace and name.	*/
	@Immutable
	static class CustomResource {
		String namespace
		String name
	}
}