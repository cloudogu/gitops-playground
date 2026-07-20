package com.cloudogu.gitops.infrastructure.kubernetes.api;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.utils.Tuple;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import jakarta.inject.Singleton;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.api.model.ProjectBuilder;
import io.fabric8.openshift.client.OpenShiftClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Kubernetes client using Fabric8 Kubernetes Client.
 */
@Singleton
@SuppressWarnings({"deprecation", "java:S3776"})
@Slf4j
public class K8sClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String DEFAULT_NAMESPACE = "default";
    private static final String INTERNAL_IP_TYPE = "InternalIP";
    private static final String DOCKER_CONFIG_JSON_TYPE = "kubernetes.io/dockerconfigjson";
    private static final String DOCKER_CONFIG_JSON_KEY = ".dockerconfigjson";

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_CHECK_INTERVAL_SECONDS = 1;
    private static final int FABRIC8_REQUEST_TIMEOUT_MILLIS = 60_000;
    private static final int FABRIC8_CONNECTION_TIMEOUT_MILLIS = 10_000;

    protected int SLEEPTIME = 1000;
    protected int DEFAULT_RETRIES = 120;

    private KubernetesClient client;
    private com.cloudogu.gitops.config.Config gopConfig;

    public K8sClient() {
        this(null);
    }

    public K8sClient(com.cloudogu.gitops.config.Config gopConfig) {
        io.fabric8.kubernetes.client.Config config = new ConfigBuilder()
                .withRequestTimeout(FABRIC8_REQUEST_TIMEOUT_MILLIS)
                .withConnectionTimeout(FABRIC8_CONNECTION_TIMEOUT_MILLIS)
                .build();

        this.client = new KubernetesClientBuilder()
                .withConfig(config)
                .build();
        this.gopConfig = gopConfig;
    }

    public void setClient(KubernetesClient client) {
        this.client = client;
    }

    public void setGopConfig(com.cloudogu.gitops.config.Config gopConfig) {
        this.gopConfig = gopConfig;
    }

    public KubernetesClient getClient() {
        return client;
    }

    public com.cloudogu.gitops.config.Config getGopConfig() {
        return gopConfig;
    }

    /**
     * Waits for the first node in the cluster to become available.
     *
     * @return The name of the first available node
     */
    public String waitForNode() {
        log.debug("Waiting for first node of the cluster to become ready");

        String nodeName = waitForResourceWithRetry("node", () -> {
            NodeList nodes = client.nodes().list();
            if (nodes != null && nodes.getItems() != null && !nodes.getItems().isEmpty()) {
                return nodes.getItems().get(0).getMetadata().getName();
            }
            return null;
        });

        log.debug("First node of the cluster is ready: {}", nodeName);
        return nodeName;
    }

    /**
     * Waits for and retrieves the internal IP address of the first node.
     */
    public String waitForInternalNodeIp() {
        String nodeName = waitForNode();
        log.debug("Waiting for internal IP of node {}", nodeName);

        String internalIp = waitForResourceWithRetry("internal IP of node " + nodeName, () -> {
            Node node = client.nodes().withName(nodeName).get();
            if (node != null && node.getStatus() != null && node.getStatus().getAddresses() != null) {
                for (NodeAddress address : node.getStatus().getAddresses()) {
                    if (INTERNAL_IP_TYPE.equals(address.getType())) {
                        return address.getAddress();
                    }
                }
            }
            return null;
        });

        log.debug("Internal IP of node {}: {}", nodeName, internalIp);
        return internalIp;
    }

    /**
     * Waits for a service's NodePort to become available.
     */
    public String waitForNodePort(String serviceName, String namespace) {
        log.debug("Getting node port for service {}, ns={}", serviceName, namespace);

        String nodePort = waitForResourceWithRetry("node port for service " + serviceName, () -> {
            Service service = client.services().inNamespace(namespace).withName(serviceName).get();
            if (service != null && service.getSpec() != null && service.getSpec().getPorts() != null && !service.getSpec().getPorts().isEmpty()) {
                Integer port = service.getSpec().getPorts().get(0).getNodePort();
                return port != null ? port.toString() : null;
            }
            return null;
        });

        log.debug("Node port for service {}, ns={}: {}", serviceName, namespace, nodePort);
        return nodePort;
    }

    public void createServiceNodePort(String name, String tcp) {
        createServiceNodePort(name, tcp, "", "");
    }

    public void createServiceNodePort(String name, String tcp, String nodePort) {
        createServiceNodePort(name, tcp, nodePort, "");
    }

    /**
     * Creates a NodePort service (idempotent).
     */
    public void createServiceNodePort(String name, String tcp, String nodePort, String namespace) {
        log.debug("Creating NodePort service {} in namespace {}", name, namespace);

        String[] ports = tcp.split(":");
        int port = Integer.parseInt(ports[0]);
        int targetPort = ports.length > 1 ? Integer.parseInt(ports[1]) : port;

        ServicePort servicePort = new ServicePortBuilder()
                .withPort(port)
                .withTargetPort(new IntOrString(targetPort))
                .build();
        if (nodePort != null && !nodePort.isEmpty()) {
            servicePort.setNodePort(Integer.parseInt(nodePort));
        }

        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(resolveNamespace(namespace))
                .endMetadata()
                .withNewSpec()
                    .withType("NodePort")
                    .withPorts(servicePort)
                .endSpec()
                .build();

        executeWithErrorHandling("create NodePort service " + name, () -> {
            client.services()
                    .inNamespace(resolveNamespace(namespace))
                    .resource(service)
                    .createOrReplace();
            return null;
        });

        log.debug("NodePort service {} created/updated successfully", name);
    }

    /**
     * Patches the nodePort of a specific port in a service.
     */
    public void patchServiceNodePort(String serviceName, String namespace, String portName, int newNodePort) {
        K8sClientHelper.validateServiceNodePortPatch(serviceName, namespace, portName, newNodePort);

        log.debug("Patching service {} port {} with nodePort {}", serviceName, portName, newNodePort);

        Service service = client.services().inNamespace(namespace).withName(serviceName).get();

        if (service == null) {
            throw new RuntimeException("Service " + serviceName + " not found in namespace " + namespace);
        }

        List<ServicePort> ports = service.getSpec().getPorts();
        int portIndex = -1;
        for (int i = 0; i < ports.size(); i++) {
            if (portName.equals(ports.get(i).getName())) {
                portIndex = i;
                break;
            }
        }

        if (portIndex == -1) {
            throw new RuntimeException("Port with name " + portName + " not found in service " + serviceName + ".");
        }

        // Create JSON patch
        List<Map<String, Object>> patch = List.of(Map.of(
                "op", "replace",
                "path", "/spec/ports/" + portIndex + "/nodePort",
                "value", newNodePort
        ));

        String patchJson = Serialization.asJson(patch);
        PatchContext patchContext = new PatchContext.Builder()
                .withPatchType(io.fabric8.kubernetes.client.dsl.base.PatchType.JSON)
                .build();

        executeWithErrorHandling("patch service " + serviceName, () -> {
            client.services()
                    .inNamespace(namespace)
                    .withName(serviceName)
                    .patch(patchContext, patchJson);
            return null;
        });

        log.debug("Service {} in namespace {} successfully patched with nodePort {} for port {}.", serviceName, namespace, newNodePort, portName);
    }

    /**
     * Creates a namespace if it does not already exist (idempotent).
     */
    public void createNamespace(String name) {
        K8sClientHelper.validateNamespaceName(name);

        if (!namespaceExists(name)) {
            log.debug("Namespace {} does not exist, proceeding to create.", name);

            if (runInOpenshift()) {
                OpenShiftClient osClient = client.adapt(OpenShiftClient.class);

                Project project = new ProjectBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .endMetadata()
                        .build();
                executeWithErrorHandling("create project " + name, () -> {
                    osClient.projects().resource(project).create();
                    return null;
                });
                log.debug("Project {} created successfully.", name);
            } else {
                Namespace namespace = new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .endMetadata()
                        .build();

                executeWithErrorHandling("create namespace " + name, () -> {
                    client.namespaces().resource(namespace).create();
                    return null;
                });

                log.debug("Namespace {} created successfully.", name);
            }
        }
    }

    /**
     * Creates multiple namespaces.
     */
    public void createNamespaces(List<String> names) {
        if (names == null) {
            throw new IllegalArgumentException("Namespaces must be provided and cannot be null.");
        }
        for (String name : names) {
            createNamespace(name);
        }
    }

    /**
     * Checks if a namespace exists.
     */
    public boolean namespaceExists(String namespace) {
        try {
            Namespace ns = client.namespaces().withName(namespace).get();
            if (ns != null) {
                log.debug("Namespace {} already exists.", namespace);
                return true;
            }
        } catch (Exception e) {
            log.trace("Namespace {} does not exist: {}", namespace, e.getMessage());
        }
        return false;
    }

    public void createSecret(String type, String name) {
        createSecret(type, name, "", new Tuple[0]);
    }

    public void createSecret(String type, String name, String namespace) {
        createSecret(type, name, namespace, new Tuple[0]);
    }

    /**
     * Creates or updates a generic secret (idempotent).
     */
    public void createSecret(String type, String name, String namespace, Tuple... literals) {
        log.debug("Creating secret {} of type {} in namespace {}", name, type, namespace);

        Map<String, String> data = new HashMap<>();
        if (literals != null) {
            for (Tuple tuple : literals) {
                data.put(String.valueOf(tuple.getFirst()), String.valueOf(tuple.getSecond()));
            }
        }

        String resolvedType = "generic".equals(type) ? "Opaque" : type;
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(resolveNamespace(namespace))
                .endMetadata()
                .withType(resolvedType)
                .withStringData(data)
                .build();

        executeWithErrorHandling("create secret " + name, () -> {
            var secretsClient = client.secrets().inNamespace(resolveNamespace(namespace));
            if (secretsClient.withName(name).get() != null) {
                secretsClient.withName(name).delete();
            }
            secretsClient.resource(secret).create();
            return null;
        });

        log.debug("Secret {} created/updated successfully", name);
    }

    public void createSecret(String type, String name, String namespace, groovy.lang.Tuple2... literals) {
        Tuple[] tuples = new Tuple[literals.length];
        for (int i = 0; i < literals.length; i++) {
            tuples[i] = new Tuple(literals[i].getFirst(), literals[i].getSecond());
        }
        createSecret(type, name, namespace, tuples);
    }

    public void createImagePullSecret(String name, String host, String user, String password) {
        createImagePullSecret(name, "", host, user, password);
    }

    /**
     * Creates or updates an image pull secret (idempotent).
     */
    public void createImagePullSecret(String name, String namespace, String host, String user, String password) {
        log.debug("Creating image pull secret {} in namespace {}", name, namespace);

        String auth = Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        String dockerConfig = "{\"auths\":{\"" + host + "\":{\"username\":\"" + user + "\",\"password\":\"" + password + "\",\"auth\":\"" + auth + "\"}}}";

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(resolveNamespace(namespace))
                .endMetadata()
                .withType(DOCKER_CONFIG_JSON_TYPE)
                .addToStringData(DOCKER_CONFIG_JSON_KEY, dockerConfig)
                .build();

        executeWithErrorHandling("create image pull secret " + name, () -> {
            client.secrets()
                    .inNamespace(resolveNamespace(namespace))
                    .resource(secret)
                    .createOrReplace();
            return null;
        });

        log.debug("Image pull secret {} created/updated successfully", name);
    }

    public String getArgoCDNamespacesSecret(String name) {
        return getArgoCDNamespacesSecret(name, "");
    }

    /**
     * Retrieves the 'namespaces' data from an ArgoCD secret.
     */
    public String getArgoCDNamespacesSecret(String name, String namespace) {
        log.debug("Getting Secret {} from namespace {}", name, namespace);

        return waitForResourceWithRetry("secret " + name, () -> {
            Secret secret = client.secrets()
                    .inNamespace(resolveNamespace(namespace))
                    .withName(name)
                    .get();

            return (secret != null && secret.getData() != null && secret.getData().containsKey("namespaces")) ? secret.getData().get("namespaces") : null;
        });
    }

    public Credentials getCredentialsFromSecret(String secretname, String namespace) {
        return getCredentialsFromSecret(secretname, namespace, "username", "password");
    }

    public Credentials getCredentialsFromSecret(String secretname, String namespace, String usernameKey) {
        return getCredentialsFromSecret(secretname, namespace, usernameKey, "password");
    }

    /**
     * Extracts credentials from a Kubernetes secret.
     */
    public Credentials getCredentialsFromSecret(String secretname, String namespace, String usernameKey, String passwordKey) {
        return executeWithErrorHandling("get credentials from secret " + secretname, () -> {
            Secret secret = client.secrets()
                    .inNamespace(namespace)
                    .withName(secretname)
                    .get();

            Map<String, String> secretData = secret.getData();
            String username = new String(Base64.getDecoder().decode(secretData.get(usernameKey)));
            String password = new String(Base64.getDecoder().decode(secretData.get(passwordKey)));
            return new Credentials(username, password);
        });
    }

    /**
     * Extracts credentials from a Kubernetes secret using a Credentials object as input.
     */
    public Credentials getCredentialsFromSecret(Credentials credentials) {
        return executeWithErrorHandling("get credentials from secret " + credentials.getSecretName(), () -> {
            Secret secret = client.secrets()
                    .inNamespace(credentials.getSecretNamespace())
                    .withName(credentials.getSecretName())
                    .get();

            Map<String, String> secretData = secret.getData();
            String usernameEncoded = secretData.get(credentials.getUsernameKey());
            String username = usernameEncoded != null ? new String(Base64.getDecoder().decode(usernameEncoded)) : credentials.getUsername();
            String password = new String(Base64.getDecoder().decode(secretData.get(credentials.getPasswordKey())));

            Credentials credentialsNew = new Credentials(credentials);
            credentialsNew.setUsername(username);
            credentialsNew.setPassword(password);

            return credentialsNew;
        });
    }

    public void createConfigMapFromFile(String name, String filePath) {
        createConfigMapFromFile(name, "", filePath);
    }

    /**
     * Creates or updates a ConfigMap from a file (idempotent).
     */
    public void createConfigMapFromFile(String name, String namespace, String filePath) {
        log.debug("Creating ConfigMap {} from file {} in namespace {}", name, filePath, namespace);

        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + filePath);
        }

        String fileContent;
        try {
            fileContent = Files.readString(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + filePath, e);
        }

        Map<String, String> data = Map.of(file.getName(), fileContent);

        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(resolveNamespace(namespace))
                .endMetadata()
                .withData(data)
                .build();

        executeWithErrorHandling("create ConfigMap " + name + " from file", () -> {
            client.configMaps()
                    .inNamespace(resolveNamespace(namespace))
                    .resource(configMap)
                    .createOrReplace();
            return null;
        });

        log.debug("ConfigMap {} created/updated successfully", name);
    }

    /**
     * Retrieves a value from a ConfigMap.
     */
    public String getConfigMap(String mapName, String key) {
        String namespace = getCurrentNamespace();

        log.debug("Getting ConfigMap {}/{}, key: {}", namespace, mapName, key);

        ConfigMap configMap = client.configMaps()
                .inNamespace(namespace)
                .withName(mapName)
                .get();

        if (configMap == null) {
            throw new RuntimeException("Could not fetch configmap " + mapName + " from namespace " + namespace);
        }

        if (configMap.getData() == null || !configMap.getData().containsKey(key)) {
            throw new RuntimeException("Could not fetch " + key + " within config-map " + mapName + " from namespace " + namespace);
        }

        return configMap.getData().get(key);
    }

    /**
     * Applies YAML resources from a file.
     */
    public String applyYaml(String yamlLocation) {
        log.debug("Applying YAML from {}", yamlLocation);

        if (yamlLocation.startsWith("http://") || yamlLocation.startsWith("https://")) {
            try {
                int appliedResources = applyYamlStream(new URL(yamlLocation).openStream(), yamlLocation);
                return "Applied " + appliedResources + " resource(s) from " + yamlLocation;
            } catch (IOException e) {
                throw new RuntimeException("Failed to apply YAML from URL: " + yamlLocation, e);
            }
        }

        File location = new File(yamlLocation);

        if (!location.exists()) {
            throw new RuntimeException("File or directory not found: " + yamlLocation);
        }

        if (location.isDirectory()) {
            List<File> yamlFiles = new ArrayList<>();
            try (var stream = Files.walk(location.toPath())) {
                stream.filter(Files::isRegularFile)
                      .map(Path::toFile)
                      .filter(f -> f.getName().endsWith(".yaml") || f.getName().endsWith(".yml"))
                      .forEach(yamlFiles::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to list YAML files in directory: " + yamlLocation, e);
            }

            yamlFiles.sort(Comparator.comparing(File::getAbsolutePath));

            int appliedResources = 0;
            for (File file : yamlFiles) {
                try {
                    appliedResources += applyYamlStream(Files.newInputStream(file.toPath()), file.getAbsolutePath());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to apply YAML file: " + file.getAbsolutePath(), e);
                }
            }

            return "Applied " + appliedResources + " resource(s) from directory " + yamlLocation;
        }

        try {
            int appliedResources = applyYamlStream(Files.newInputStream(location.toPath()), yamlLocation);
            return "Applied " + appliedResources + " resource(s) from " + yamlLocation;
        } catch (IOException e) {
            throw new RuntimeException("Failed to apply YAML file: " + yamlLocation, e);
        }
    }

    private int applyYamlStream(InputStream stream, String sourceDescription) {
        List<HasMetadata> resources = executeWithErrorHandling("load YAML from " + sourceDescription, () -> {
            try {
                return client.load(stream).items();
            } finally {
                try {
                    stream.close();
                } catch (IOException ignored) {}
            }
        });

        for (HasMetadata resource : resources) {
            executeWithErrorHandling("apply resource from " + sourceDescription, () -> {
                client.resource(resource).createOrReplace();
                return null;
            });
        }

        return resources.size();
    }

    public void label(String resource, String name, Tuple... keyValues) {
        label(resource, name, "", keyValues);
    }

    public void label(String resource, String name, groovy.lang.Tuple2... keyValues) {
        Tuple[] tuples = new Tuple[keyValues.length];
        for (int i = 0; i < keyValues.length; i++) {
            tuples[i] = new Tuple(keyValues[i].getFirst(), keyValues[i].getSecond());
        }
        label(resource, name, tuples);
    }

    public void label(String resource, String name, String namespace, Tuple... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            throw new RuntimeException("Missing key-value-pairs");
        }

        if ("--all".equals(name)) {
            NodeList nodes = client.nodes().list();
            if (nodes != null && nodes.getItems() != null) {
                for (Node node : nodes.getItems()) {
                    label(resource, node.getMetadata().getName(), namespace, keyValues);
                }
            }
            return;
        }

        log.debug("Labeling {}/{} in namespace {}", resource, name, namespace);

        Map<String, String> labelsToAdd = new HashMap<>();
        List<String> labelsToRemove = new ArrayList<>();

        for (Tuple tuple : keyValues) {
            String key = String.valueOf(tuple.getFirst());
            String value = String.valueOf(tuple.getSecond());

            if (key.endsWith("-")) {
                labelsToRemove.add(key.substring(0, key.length() - 1));
            } else {
                labelsToAdd.put(key, value);
            }
        }

        executeWithErrorHandling("label " + resource + "/" + name, () -> {
            var resourceClient = K8sClientHelper.getResourceClient(client, resource, name, resolveNamespace(namespace));
            HasMetadata existingResource = (HasMetadata) resourceClient.get();

            if (existingResource == null) {
                throw new RuntimeException("Resource " + resource + "/" + name + " not found");
            }

            Map<String, String> existingLabels = existingResource.getMetadata().getLabels();
            if (existingLabels == null) {
                existingLabels = new HashMap<>();
            } else {
                existingLabels = new HashMap<>(existingLabels); // ensure mutable
            }

            for (String key : labelsToRemove) {
                existingLabels.remove(key);
            }
            existingLabels.putAll(labelsToAdd);

            existingResource.getMetadata().setLabels(existingLabels);
            resourceClient.replace(existingResource);
            return null;
        });

        log.debug("Labels updated successfully");
    }

    public void label(String resource, String name, String namespace, groovy.lang.Tuple2... keyValues) {
        Tuple[] tuples = new Tuple[keyValues.length];
        for (int i = 0; i < keyValues.length; i++) {
            tuples[i] = new Tuple(keyValues[i].getFirst(), keyValues[i].getSecond());
        }
        label(resource, name, namespace, tuples);
    }

    public void labelRemove(String resource, String name) {
        labelRemove(resource, name, "", new String[0]);
    }

    public void labelRemove(String resource, String name, String namespace) {
        labelRemove(resource, name, namespace, new String[0]);
    }

    public void labelRemove(String resource, String name, String namespace, String... keys) {
        Tuple[] tuples = new Tuple[keys.length];
        for (int i = 0; i < keys.length; i++) {
            tuples[i] = new Tuple(keys[i] + "-", "");
        }
        label(resource, name, namespace, tuples);
    }

    public void patch(String resource, String name, Map<String, Object> yaml) {
        patch(resource, name, "", "", yaml);
    }

    public void patch(String resource, String name, String namespace, Map<String, Object> yaml) {
        patch(resource, name, namespace, "", yaml);
    }

    public void patch(String resource, String name, String namespace, String type, Map<String, Object> yaml) {
        log.debug("Patching {}/{} in namespace {}", resource, name, namespace);

        PatchContext patchContext = K8sClientHelper.createPatchContext(type);
        String patchJson = Serialization.asJson(yaml);
        log.trace("Patch JSON: {}", patchJson);

        executeWithErrorHandling("patch " + resource + "/" + name, () -> {
            var resourceClient = K8sClientHelper.getResourceClient(client, resource, name, resolveNamespace(namespace));
            resourceClient.patch(patchContext, patchJson);
            return null;
        });

        log.debug("Resource {}/{} patched successfully", resource, name);
    }

    public void delete(String resource) {
        delete(resource, "", new Tuple[0]);
    }

    public void delete(String resource, String namespace) {
        delete(resource, namespace, new Tuple[0]);
    }

    public void delete(String resource, String namespace, Tuple... selectors) {
        if (selectors == null || selectors.length == 0) {
            throw new RuntimeException("Missing selectors");
        }

        log.debug("Deleting {} in namespace {} with selectors", resource, namespace);

        Map<String, String> labels = new HashMap<>();
        for (Tuple tuple : selectors) {
            labels.put(String.valueOf(tuple.getFirst()), String.valueOf(tuple.getSecond()));
        }

        try {
            K8sClientHelper.deleteResourcesByType(client, resource, resolveNamespace(namespace), labels);
            log.debug("Resources deleted successfully");
        } catch (Exception e) {
            log.warn("Failed to delete resources (may not exist): {}", e.getMessage());
        }
    }

    public void delete(String resource, String namespace, groovy.lang.Tuple2... selectors) {
        Tuple[] tuples = new Tuple[selectors.length];
        for (int i = 0; i < selectors.length; i++) {
            tuples[i] = new Tuple(selectors[i].getFirst(), selectors[i].getSecond());
        }
        delete(resource, namespace, tuples);
    }

    public void delete(String resource, String namespace, String name) {
        log.debug("Deleting {}/{} in namespace {}", resource, name, namespace);

        try {
            var resourceClient = K8sClientHelper.getResourceClient(client, resource, name, resolveNamespace(namespace));
            resourceClient.delete();
            log.debug("Resource {}/{} deleted successfully", resource, name);
        } catch (Exception e) {
            log.warn("Failed to delete resource (may not exist): {}", e.getMessage());
        }
    }

    public String run(String name, String image) {
        return run(name, image, "", Map.of(), new String[0]);
    }

    public String run(String name, String image, String namespace) {
        return run(name, image, namespace, Map.of(), new String[0]);
    }

    public String run(String name, String image, String namespace, Map<String, ?> overrides) {
        return run(name, image, namespace, overrides, new String[0]);
    }

    public String run(String name, String image, String namespace, String... params) {
        return run(name, image, namespace, Map.of(), params);
    }

    public String run(String name, String image, String namespace, Map<String, ?> overrides, String... params) {
        log.debug("Running pod {} with image {} in namespace {}", name, image, namespace);
        String resolvedNamespace = resolveNamespace(namespace);
        List<String> runParams = params != null ? Arrays.asList(params) : Collections.emptyList();

        Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(resolvedNamespace)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(name)
                .withImage(image)
                .endContainer()
                .endSpec()
                .build();

        K8sClientHelper.applyRunParams(pod, runParams);

        if (overrides != null && !overrides.isEmpty()) {
            log.debug("Applying overrides: {}", overrides);
            pod = K8sClientHelper.applyPodOverrides(pod, overrides);
        }

        final Pod finalPod = pod;
        Pod createdPod = executeWithErrorHandling("run pod " + name, () -> client.pods()
                .inNamespace(resolvedNamespace)
                .resource(finalPod)
                .create());

        log.debug("Pod {} created successfully", name);
        if (K8sClientHelper.shouldReturnPodOutput(runParams)) {
            return K8sClientHelper.collectPodRunOutput(client, createdPod.getMetadata().getName(), resolvedNamespace, K8sClientHelper.shouldRemovePod(runParams), DEFAULT_RETRIES, SLEEPTIME, this);
        }

        return "pod/" + createdPod.getMetadata().getName() + " created";
    }

    public List<CustomResource> getCustomResource(String resource) {
        log.debug("Getting custom resources of type {}", resource);

        try {
            Map<String, Object> match = K8sClientHelper.findApiResourceViaDiscovery(client, resource.toLowerCase(), resource);
            if (match == null) {
                return Collections.emptyList();
            }
            ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                    .withGroup((String) match.get("group"))
                    .withVersion((String) match.get("version"))
                    .withKind((String) match.get("kind"))
                    .withPlural((String) match.get("plural"))
                    .withNamespaced((Boolean) match.get("namespaced"))
                    .build();

            var apiClient = client.genericKubernetesResources(context);
            var resourceList = apiClient.inAnyNamespace().list();

            if (resourceList == null || resourceList.getItems() == null) {
                return Collections.emptyList();
            }

            return resourceList.getItems().stream().map(item -> {
                Map<String, Object> metadata = item.getMetadata() != null ? Serialization.unmarshal(Serialization.asJson(item.getMetadata()), MAP_TYPE) : Collections.emptyMap();
                String ns = metadata.containsKey("namespace") ? String.valueOf(metadata.get("namespace")) : "";
                String name = metadata.containsKey("name") ? String.valueOf(metadata.get("name")) : "";
                return new CustomResource(ns, name);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to get custom resources: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public String getAnnotation(String resource, String name, String key) {
        return getAnnotation(resource, name, key, "");
    }

    public String getAnnotation(String resource, String name, String key, String namespace) {
        log.debug("Getting annotation {} from {}/{} in namespace {}", key, resource, name, namespace);

        var resourceClient = K8sClientHelper.getResourceClient(client, resource, name, resolveNamespace(namespace));
        HasMetadata k8sResource = (HasMetadata) resourceClient.get();

        if (k8sResource == null) {
            throw new RuntimeException("Resource " + resource + "/" + name + " not found");
        }

        Map<String, String> annotations = k8sResource.getMetadata().getAnnotations();
        if (annotations == null) {
            throw new RuntimeException("No annotations found on resource " + resource + "/" + name);
        }

        String value = annotations.get(key);
        log.debug("getAnnotation returns = {}", value);
        return value;
    }

    public String getCurrentContext() {
        try {
            var currentContext = client.getConfiguration().getCurrentContext();
            String context = currentContext != null ? currentContext.getName() : null;
            return context != null ? context : "(current context not set)";
        } catch (Exception e) {
            log.trace("Failed to get current context: {}", e.getMessage());
            return "(current context not set)";
        }
    }

    public void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase) {
        waitForResourcePhase(resourceType, resourceName, namespace, desiredPhase, DEFAULT_TIMEOUT_SECONDS, DEFAULT_CHECK_INTERVAL_SECONDS);
    }

    public void waitForResourcePhase(String resourceType, String resourceName, String namespace, String desiredPhase,
                                     int timeoutSeconds, int checkIntervalSeconds) {
        K8sClientHelper.validateWaitForResourcePhaseParams(resourceType, resourceName, namespace, desiredPhase, timeoutSeconds, checkIntervalSeconds);

        log.debug("Waiting for {}/{} to reach phase {}", resourceType, resourceName, desiredPhase);

        long startTime = System.currentTimeMillis();
        long endTime = startTime + ((long) timeoutSeconds * 1000);

        while (System.currentTimeMillis() < endTime) {
            try {
                var resourceClient = K8sClientHelper.getResourceClient(client, resourceType, resourceName, resolveNamespace(namespace));
                HasMetadata resource = (HasMetadata) resourceClient.get();

                if (resource != null) {
                    String phase = extractPhase(resource);

                    if (desiredPhase.equals(phase)) {
                        log.debug("Resource {}/{} in namespace {} reached the desired phase: {}", resourceType, resourceName, namespace, desiredPhase);
                        return;
                    }

                    log.debug("Current phase: {}. Waiting for phase: {}...", phase, desiredPhase);
                }
            } catch (Exception e) {
                log.trace("Error checking resource phase: {}", e.getMessage());
            }

            try {
                Thread.sleep((long) checkIntervalSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for resource phase", e);
            }
        }

        throw new RuntimeException("Timeout reached. Resource " + resourceType + "/" + resourceName + " in namespace " + namespace +
                " did not reach the desired phase: " + desiredPhase + " within " + timeoutSeconds + " seconds.");
    }

    private String extractPhase(HasMetadata resource) {
        if (resource instanceof Pod pod) {
            return pod.getStatus() != null ? pod.getStatus().getPhase() : null;
        }

        // Generic / Custom Resources
        Map<String, Object> status = Serialization.unmarshal(Serialization.asJson(resource), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> statusMap = (Map<String, Object>) status.get("status");
        return statusMap != null ? (String) statusMap.get("phase") : null;
    }

    private <T> T waitForResourceWithRetry(String resourceDescription, Supplier<T> fetchSupplier) {
        int tryCount = 0;
        T result = null;

        while (result == null && tryCount < DEFAULT_RETRIES) {
            try {
                result = fetchSupplier.get();
            } catch (Exception e) {
                log.trace("Error fetching {}: {}", resourceDescription, e.getMessage());
            }

            if (result == null) {
                tryCount++;
                log.debug("Still waiting for {}... (try {}/{})", resourceDescription, tryCount, DEFAULT_RETRIES);
                try {
                    Thread.sleep(SLEEPTIME);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting", e);
                }
            }
        }

        if (result == null) {
            throw new RuntimeException("Failed to retrieve " + resourceDescription + " after " + DEFAULT_RETRIES + " retries");
        }

        return result;
    }

    private <T> T executeWithErrorHandling(String operation, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to " + operation + ": " + e.getMessage(), e);
        }
    }

    private String resolveNamespace(String namespace) {
        return namespace != null && !namespace.isEmpty() ? namespace : DEFAULT_NAMESPACE;
    }

    public String getCurrentNamespace() {
        return this.client.getNamespace();
    }

    private boolean runInOpenshift() {
        return this.gopConfig != null && this.gopConfig.getApplication() != null && Boolean.TRUE.equals(this.gopConfig.getApplication().getOpenshift());
    }

    public static class CustomResource {
        private final String namespace;
        private final String name;

        public CustomResource(String namespace, String name) {
            this.namespace = namespace;
            this.name = name;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getName() {
            return name;
        }
    }

    public static class KubernetesApiResourceNotFoundException extends RuntimeException {
        public KubernetesApiResourceNotFoundException(String resourceType) {
            super("No API resource found for custom resource type '" + resourceType + "'");
        }
    }
}
