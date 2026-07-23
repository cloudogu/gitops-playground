package com.cloudogu.gitops.infrastructure.kubernetes.api;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.Tuple;
import com.fasterxml.jackson.core.type.TypeReference;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.api.model.ProjectBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/** Kubernetes client using Fabric8 Kubernetes Client. */
@Singleton
@SuppressWarnings("java:S3776")
@Slf4j
public class K8sClient {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static final String DEFAULT_NAMESPACE = "default";
  private static final String INTERNAL_IP_TYPE = "InternalIP";
  private static final String DOCKER_CONFIG_JSON_TYPE = "kubernetes.io/dockerconfigjson";
  private static final String DOCKER_CONFIG_JSON_KEY = ".dockerconfigjson";
  private static final String NOT_FOUND_IN_NAMESPACE = " not found in namespace ";
  private static final String APPLIED_PREFIX = "Applied ";

  private static final int DEFAULT_TIMEOUT_SECONDS = 60;
  private static final int DEFAULT_CHECK_INTERVAL_SECONDS = 1;
  private static final int FABRIC8_REQUEST_TIMEOUT_MILLIS = 60_000;
  private static final int FABRIC8_CONNECTION_TIMEOUT_MILLIS = 10_000;
  private static final int MILLIS_PER_SECOND = 1000;
  private static final int DEFAULT_SLEEP_TIME_MILLIS = MILLIS_PER_SECOND;
  private static final int DEFAULT_RETRIES = 120;

  protected int sleepTimeMillis = DEFAULT_SLEEP_TIME_MILLIS;
  protected int defaultRetries = DEFAULT_RETRIES;

  private KubernetesClient client;
  private com.cloudogu.gitops.config.Config gopConfig;

  /** Creates a client with default fabric8 configuration and no playground config. */
  public K8sClient() {
    this(null);
  }

  /**
   * Creates a client with default fabric8 configuration.
   *
   * @param gopConfig the GitOps Playground config, used e.g. to detect OpenShift mode; may be null
   */
  public K8sClient(com.cloudogu.gitops.config.Config gopConfig) {
    io.fabric8.kubernetes.client.Config config =
        new ConfigBuilder()
            .withRequestTimeout(FABRIC8_REQUEST_TIMEOUT_MILLIS)
            .withConnectionTimeout(FABRIC8_CONNECTION_TIMEOUT_MILLIS)
            .build();

    this.client = new KubernetesClientBuilder().withConfig(config).build();
    this.gopConfig = gopConfig;
  }

  /**
   * Replaces the underlying fabric8 client, mainly for tests.
   *
   * @param client the fabric8 client to use
   */
  public void setClient(KubernetesClient client) {
    this.client = client;
  }

  /**
   * Sets the GitOps Playground config after construction.
   *
   * @param gopConfig the GitOps Playground config; may be null
   */
  public void setGopConfig(com.cloudogu.gitops.config.Config gopConfig) {
    this.gopConfig = gopConfig;
  }

  /**
   * Returns the underlying fabric8 client.
   *
   * @return the fabric8 client
   */
  public KubernetesClient getClient() {
    return client;
  }

  /**
   * Returns the GitOps Playground config.
   *
   * @return the config; may be null
   */
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

    String nodeName =
        waitForResourceWithRetry(
            "node",
            () -> {
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
   *
   * @return the internal IP address of the first node
   */
  public String waitForInternalNodeIp() {
    String nodeName = waitForNode();
    log.debug("Waiting for internal IP of node {}", nodeName);

    String internalIp =
        waitForResourceWithRetry(
            "internal IP of node " + nodeName, () -> findInternalNodeIp(nodeName));

    log.debug("Internal IP of node {}: {}", nodeName, internalIp);
    return internalIp;
  }

  private String findInternalNodeIp(String nodeName) {
    Node node = client.nodes().withName(nodeName).get();
    if (node != null && node.getStatus() != null && node.getStatus().getAddresses() != null) {
      for (NodeAddress address : node.getStatus().getAddresses()) {
        if (INTERNAL_IP_TYPE.equals(address.getType())) {
          return address.getAddress();
        }
      }
    }
    return null;
  }

  /**
   * Waits for a service's NodePort to become available.
   *
   * @param serviceName name of the service to inspect
   * @param namespace namespace of the service; empty means the default namespace
   * @return the NodePort of the service's first port
   */
  public String waitForNodePort(String serviceName, String namespace) {
    log.debug("Getting node port for service {}, ns={}", serviceName, namespace);

    String nodePort =
        waitForResourceWithRetry(
            "node port for service " + serviceName,
            () -> findServiceNodePort(serviceName, namespace));

    log.debug("Node port for service {}, ns={}: {}", serviceName, namespace, nodePort);
    return nodePort;
  }

  private String findServiceNodePort(String serviceName, String namespace) {
    Service service = client.services().inNamespace(namespace).withName(serviceName).get();
    if (service != null
        && service.getSpec() != null
        && service.getSpec().getPorts() != null
        && !service.getSpec().getPorts().isEmpty()) {
      Integer port = service.getSpec().getPorts().get(0).getNodePort();
      return port != null ? port.toString() : null;
    }
    return null;
  }

  /**
   * Creates a NodePort service in the default namespace with an auto-assigned node port
   * (idempotent).
   *
   * @param name name of the service to create
   * @param tcp port mapping in the form {@code port[:targetPort]}
   */
  public void createServiceNodePort(String name, String tcp) {
    createServiceNodePort(name, tcp, "", "");
  }

  /**
   * Creates a NodePort service in the default namespace (idempotent).
   *
   * @param name name of the service to create
   * @param tcp port mapping in the form {@code port[:targetPort]}
   * @param nodePort fixed node port to expose; empty for auto-assignment
   */
  public void createServiceNodePort(String name, String tcp, String nodePort) {
    createServiceNodePort(name, tcp, nodePort, "");
  }

  /**
   * Creates a NodePort service (idempotent).
   *
   * @param name name of the service to create
   * @param tcp port mapping in the form {@code port[:targetPort]}
   * @param nodePort fixed node port to expose; empty for auto-assignment
   * @param namespace target namespace; empty means the default namespace
   */
  public void createServiceNodePort(String name, String tcp, String nodePort, String namespace) {
    log.debug("Creating NodePort service {} in namespace {}", name, namespace);

    String[] ports = tcp.split(":");
    int port = Integer.parseInt(ports[0]);
    int targetPort = ports.length > 1 ? Integer.parseInt(ports[1]) : port;

    ServicePort servicePort =
        new ServicePortBuilder().withPort(port).withTargetPort(new IntOrString(targetPort)).build();
    if (nodePort != null && !nodePort.isEmpty()) {
      servicePort.setNodePort(Integer.parseInt(nodePort));
    }

    Service service =
        new ServiceBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(resolveNamespace(namespace))
            .endMetadata()
            .withNewSpec()
            .withType("NodePort")
            .withPorts(servicePort)
            .endSpec()
            .build();

    executeWithErrorHandling(
        "create NodePort service " + name,
        () -> {
          client
              .services()
              .inNamespace(resolveNamespace(namespace))
              .resource(service)
              .createOr(NonDeletingOperation::update);
          return null;
        });

    log.debug("NodePort service {} created/updated successfully", name);
  }

  /**
   * Patches the nodePort of a specific port in a service.
   *
   * @param serviceName name of the service to patch
   * @param namespace namespace of the service
   * @param portName name of the port entry whose nodePort is replaced
   * @param newNodePort new node port value
   */
  public void patchServiceNodePort(
      String serviceName, String namespace, String portName, int newNodePort) {
    K8sClientHelper.validateServiceNodePortPatch(serviceName, namespace, portName, newNodePort);

    log.debug("Patching service {} port {} with nodePort {}", serviceName, portName, newNodePort);

    Service service = client.services().inNamespace(namespace).withName(serviceName).get();

    if (service == null) {
      throw new IllegalStateException(
          "Service " + serviceName + NOT_FOUND_IN_NAMESPACE + namespace);
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
      throw new IllegalStateException(
          "Port with name " + portName + " not found in service " + serviceName + ".");
    }

    // Create JSON patch
    List<Map<String, Object>> patch =
        List.of(
            Map.of(
                "op",
                "replace",
                "path",
                "/spec/ports/" + portIndex + "/nodePort",
                "value",
                newNodePort));

    String patchJson = Serialization.asJson(patch);
    PatchContext patchContext =
        new PatchContext.Builder()
            .withPatchType(io.fabric8.kubernetes.client.dsl.base.PatchType.JSON)
            .build();

    executeWithErrorHandling(
        "patch service " + serviceName,
        () -> {
          client
              .services()
              .inNamespace(namespace)
              .withName(serviceName)
              .patch(patchContext, patchJson);
          return null;
        });

    log.debug(
        "Service {} in namespace {} successfully patched with nodePort {} for port {}.",
        serviceName,
        namespace,
        newNodePort,
        portName);
  }

  /**
   * Creates a namespace (or an OpenShift project) if it does not already exist (idempotent).
   *
   * @param name name of the namespace to create
   */
  public void createNamespace(String name) {
    K8sClientHelper.validateNamespaceName(name);

    if (!namespaceExists(name)) {
      log.debug("Namespace {} does not exist, proceeding to create.", name);

      if (runInOpenshift()) {
        OpenShiftClient osClient = client.adapt(OpenShiftClient.class);

        Project project =
            new ProjectBuilder().withNewMetadata().withName(name).endMetadata().build();
        executeWithErrorHandling(
            "create project " + name,
            () -> {
              osClient.projects().resource(project).create();
              return null;
            });
        log.debug("Project {} created successfully.", name);
      } else {
        Namespace namespace =
            new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build();

        executeWithErrorHandling(
            "create namespace " + name,
            () -> {
              client.namespaces().resource(namespace).create();
              return null;
            });

        log.debug("Namespace {} created successfully.", name);
      }
    }
  }

  /**
   * Creates multiple namespaces.
   *
   * @param names names of the namespaces to create
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
   *
   * @param namespace name of the namespace to check
   * @return true if the namespace exists
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

  /**
   * Creates or updates an empty secret in the default namespace (idempotent).
   *
   * @param type secret type, e.g. {@code generic}
   * @param name name of the secret
   */
  public void createSecret(String type, String name) {
    createSecret(type, name, "", new Tuple<?, ?>[0]);
  }

  /**
   * Creates or updates an empty secret (idempotent).
   *
   * @param type secret type, e.g. {@code generic}
   * @param name name of the secret
   * @param namespace target namespace; empty means the default namespace
   */
  public void createSecret(String type, String name, String namespace) {
    createSecret(type, name, namespace, new Tuple<?, ?>[0]);
  }

  /**
   * Creates or updates a generic secret (idempotent).
   *
   * @param type secret type; {@code generic} is mapped to {@code Opaque}
   * @param name name of the secret
   * @param namespace target namespace; empty means the default namespace
   * @param literals key-value pairs stored as string data
   */
  public void createSecret(String type, String name, String namespace, Tuple<?, ?>... literals) {
    log.debug("Creating secret {} of type {} in namespace {}", name, type, namespace);

    Map<String, String> data = new HashMap<>();
    if (literals != null) {
      for (Tuple<?, ?> tuple : literals) {
        data.put(String.valueOf(tuple.getFirst()), String.valueOf(tuple.getSecond()));
      }
    }

    String resolvedType = "generic".equals(type) ? "Opaque" : type;
    Secret secret =
        new SecretBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(resolveNamespace(namespace))
            .endMetadata()
            .withType(resolvedType)
            .withStringData(data)
            .build();

    executeWithErrorHandling(
        "create secret " + name,
        () -> {
          // type is NonNamespaceOperation<Secret, SecretList, Resource<Secret>>; kept as `var`
          // deliberately, spelling it out would hurt readability more than it helps.
          var secretsClient = client.secrets().inNamespace(resolveNamespace(namespace));
          if (secretsClient.withName(name).get() != null) {
            secretsClient.withName(name).delete();
          }
          secretsClient.resource(secret).create();
          return null;
        });

    log.debug("Secret {} created/updated successfully", name);
  }

  /**
   * Creates or updates an image pull secret in the default namespace (idempotent).
   *
   * @param name name of the secret
   * @param host registry host the credentials belong to
   * @param user registry username
   * @param password registry password
   */
  public void createImagePullSecret(String name, String host, String user, String password) {
    createImagePullSecret(name, "", host, user, password);
  }

  /**
   * Creates or updates an image pull secret (idempotent).
   *
   * @param name name of the secret
   * @param namespace target namespace; empty means the default namespace
   * @param host registry host the credentials belong to
   * @param user registry username
   * @param password registry password
   */
  public void createImagePullSecret(
      String name, String namespace, String host, String user, String password) {
    log.debug("Creating image pull secret {} in namespace {}", name, namespace);

    String auth =
        Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    String dockerConfig =
        "{\"auths\":{\""
            + host
            + "\":{\"username\":\""
            + user
            + "\",\"password\":\""
            + password
            + "\",\"auth\":\""
            + auth
            + "\"}}}";

    Secret secret =
        new SecretBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(resolveNamespace(namespace))
            .endMetadata()
            .withType(DOCKER_CONFIG_JSON_TYPE)
            .addToStringData(DOCKER_CONFIG_JSON_KEY, dockerConfig)
            .build();

    executeWithErrorHandling(
        "create image pull secret " + name,
        () -> {
          client
              .secrets()
              .inNamespace(resolveNamespace(namespace))
              .resource(secret)
              .createOr(NonDeletingOperation::update);
          return null;
        });

    log.debug("Image pull secret {} created/updated successfully", name);
  }

  /**
   * Retrieves the {@code namespaces} data from an ArgoCD secret in the default namespace.
   *
   * @param name name of the secret
   * @return the base64-encoded {@code namespaces} value of the secret
   */
  public String getArgoCDNamespacesSecret(String name) {
    return getArgoCDNamespacesSecret(name, "");
  }

  /**
   * Retrieves the {@code namespaces} data from an ArgoCD secret, waiting for the secret to appear.
   *
   * @param name name of the secret
   * @param namespace namespace of the secret; empty means the default namespace
   * @return the base64-encoded {@code namespaces} value of the secret
   */
  public String getArgoCDNamespacesSecret(String name, String namespace) {
    log.debug("Getting Secret {} from namespace {}", name, namespace);

    return waitForResourceWithRetry(
        "secret " + name,
        () -> {
          Secret secret =
              client.secrets().inNamespace(resolveNamespace(namespace)).withName(name).get();

          return (secret != null
                  && secret.getData() != null
                  && secret.getData().containsKey("namespaces"))
              ? secret.getData().get("namespaces")
              : null;
        });
  }

  /**
   * Extracts credentials from a secret using the default keys {@code username} and {@code
   * password}.
   *
   * @param secretname name of the secret
   * @param namespace namespace of the secret
   * @return the decoded credentials
   */
  public Credentials getCredentialsFromSecret(String secretname, String namespace) {
    return getCredentialsFromSecret(secretname, namespace, "username", "password");
  }

  /**
   * Extracts credentials from a secret using the default password key {@code password}.
   *
   * @param secretname name of the secret
   * @param namespace namespace of the secret
   * @param usernameKey data key holding the username
   * @return the decoded credentials
   */
  public Credentials getCredentialsFromSecret(
      String secretname, String namespace, String usernameKey) {
    return getCredentialsFromSecret(secretname, namespace, usernameKey, "password");
  }

  /**
   * Extracts credentials from a Kubernetes secret.
   *
   * @param secretname name of the secret
   * @param namespace namespace of the secret
   * @param usernameKey data key holding the username
   * @param passwordKey data key holding the password
   * @return the decoded credentials
   */
  public Credentials getCredentialsFromSecret(
      String secretname, String namespace, String usernameKey, String passwordKey) {
    return executeWithErrorHandling(
        "get credentials from secret " + secretname,
        () -> resolveCredentialsFromSecret(secretname, namespace, usernameKey, passwordKey));
  }

  private Credentials resolveCredentialsFromSecret(
      String secretname, String namespace, String usernameKey, String passwordKey) {
    Secret secret = client.secrets().inNamespace(namespace).withName(secretname).get();
    if (secret == null || secret.getData() == null) {
      throw new IllegalStateException("Secret " + secretname + NOT_FOUND_IN_NAMESPACE + namespace);
    }

    Map<String, String> secretData = secret.getData();
    String username =
        new String(Base64.getDecoder().decode(secretData.get(usernameKey)), StandardCharsets.UTF_8);
    String password =
        new String(Base64.getDecoder().decode(secretData.get(passwordKey)), StandardCharsets.UTF_8);
    return new Credentials(username, password);
  }

  /**
   * Extracts credentials from a Kubernetes secret using a Credentials object as input.
   *
   * @param credentials reference describing secret name, namespace and data keys
   * @return a copy of the input with username and password resolved from the secret
   */
  public Credentials getCredentialsFromSecret(Credentials credentials) {
    return executeWithErrorHandling(
        "get credentials from secret " + credentials.getSecretName(),
        () -> resolveCredentialsFromSecret(credentials));
  }

  private Credentials resolveCredentialsFromSecret(Credentials credentials) {
    Secret secret =
        client
            .secrets()
            .inNamespace(credentials.getSecretNamespace())
            .withName(credentials.getSecretName())
            .get();
    if (secret == null || secret.getData() == null) {
      throw new IllegalStateException(
          "Secret "
              + credentials.getSecretName()
              + NOT_FOUND_IN_NAMESPACE
              + credentials.getSecretNamespace());
    }

    Map<String, String> secretData = secret.getData();
    String usernameEncoded = secretData.get(credentials.getUsernameKey());
    String username =
        usernameEncoded != null
            ? new String(Base64.getDecoder().decode(usernameEncoded), StandardCharsets.UTF_8)
            : credentials.getUsername();
    String password =
        new String(
            Base64.getDecoder().decode(secretData.get(credentials.getPasswordKey())),
            StandardCharsets.UTF_8);

    Credentials credentialsNew = new Credentials(credentials);
    credentialsNew.setUsername(username);
    credentialsNew.setPassword(password);

    return credentialsNew;
  }

  /**
   * Creates or updates a ConfigMap from a file in the default namespace (idempotent).
   *
   * @param name name of the ConfigMap
   * @param filePath path of the file whose content becomes the ConfigMap data
   */
  public void createConfigMapFromFile(String name, String filePath) {
    createConfigMapFromFile(name, "", filePath);
  }

  /**
   * Creates or updates a ConfigMap from a file (idempotent).
   *
   * @param name name of the ConfigMap
   * @param namespace target namespace; empty means the default namespace
   * @param filePath path of the file whose content becomes the ConfigMap data
   */
  public void createConfigMapFromFile(String name, String namespace, String filePath) {
    log.debug("Creating ConfigMap {} from file {} in namespace {}", name, filePath, namespace);

    File file = new File(filePath);
    if (!file.exists()) {
      throw new IllegalStateException("File not found: " + filePath);
    }

    String fileContent;
    try {
      fileContent = Files.readString(file.toPath());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read file: " + filePath, e);
    }

    Map<String, String> data = Map.of(file.getName(), fileContent);

    ConfigMap configMap =
        new ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(resolveNamespace(namespace))
            .endMetadata()
            .withData(data)
            .build();

    executeWithErrorHandling(
        "create ConfigMap " + name + " from file",
        () -> {
          client
              .configMaps()
              .inNamespace(resolveNamespace(namespace))
              .resource(configMap)
              .createOr(NonDeletingOperation::update);
          return null;
        });

    log.debug("ConfigMap {} created/updated successfully", name);
  }

  /**
   * Retrieves a value from a ConfigMap in the current namespace.
   *
   * @param mapName name of the ConfigMap
   * @param key data key to read
   * @return the value stored under the given key
   */
  public String getConfigMap(String mapName, String key) {
    String namespace = getCurrentNamespace();

    log.debug("Getting ConfigMap {}/{}, key: {}", namespace, mapName, key);

    ConfigMap configMap = client.configMaps().inNamespace(namespace).withName(mapName).get();

    if (configMap == null) {
      throw new IllegalStateException(
          "Could not fetch configmap " + mapName + " from namespace " + namespace);
    }

    if (configMap.getData() == null || !configMap.getData().containsKey(key)) {
      throw new IllegalStateException(
          "Could not fetch "
              + key
              + " within config-map "
              + mapName
              + " from namespace "
              + namespace);
    }

    return configMap.getData().get(key);
  }

  /**
   * Applies YAML resources from a URL, file or directory (recursively).
   *
   * @param yamlLocation http(s) URL, file path or directory path containing YAML resources
   * @return a summary of how many resources were applied
   */
  public String applyYaml(String yamlLocation) {
    log.debug("Applying YAML from {}", yamlLocation);

    if (yamlLocation.startsWith("http://") || yamlLocation.startsWith("https://")) {
      try {
        int appliedResources = applyYamlStream(new URL(yamlLocation).openStream(), yamlLocation);
        return APPLIED_PREFIX + appliedResources + " resource(s) from " + yamlLocation;
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to apply YAML from URL: " + yamlLocation, e);
      }
    }

    File location = new File(yamlLocation);

    if (!location.exists()) {
      throw new IllegalStateException("File or directory not found: " + yamlLocation);
    }

    if (location.isDirectory()) {
      List<File> yamlFiles;
      try (Stream<Path> stream = Files.walk(location.toPath())) {
        yamlFiles =
            stream
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .filter(file -> file.getName().endsWith(".yaml") || file.getName().endsWith(".yml"))
                .collect(Collectors.toCollection(ArrayList::new));
      } catch (IOException e) {
        throw new UncheckedIOException(
            "Failed to list YAML files in directory: " + yamlLocation, e);
      }

      yamlFiles.sort(Comparator.comparing(File::getAbsolutePath));

      int appliedResources = 0;
      for (File file : yamlFiles) {
        try {
          appliedResources +=
              applyYamlStream(Files.newInputStream(file.toPath()), file.getAbsolutePath());
        } catch (IOException e) {
          throw new UncheckedIOException("Failed to apply YAML file: " + file.getAbsolutePath(), e);
        }
      }

      return APPLIED_PREFIX + appliedResources + " resource(s) from directory " + yamlLocation;
    }

    try {
      int appliedResources = applyYamlStream(Files.newInputStream(location.toPath()), yamlLocation);
      return APPLIED_PREFIX + appliedResources + " resource(s) from " + yamlLocation;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to apply YAML file: " + yamlLocation, e);
    }
  }

  private int applyYamlStream(InputStream stream, String sourceDescription) {
    List<HasMetadata> resources =
        executeWithErrorHandling(
            "load YAML from " + sourceDescription, () -> loadYamlItems(stream, sourceDescription));

    for (HasMetadata resource : resources) {
      executeWithErrorHandling(
          "apply resource from " + sourceDescription,
          () -> {
            client.resource(resource).createOr(NonDeletingOperation::update);
            return null;
          });
    }

    return resources.size();
  }

  private List<HasMetadata> loadYamlItems(InputStream stream, String sourceDescription) {
    try {
      return client.load(stream).items();
    } finally {
      try {
        stream.close();
      } catch (IOException e) {
        log.debug("Failed to close YAML input stream for {}", sourceDescription, e);
      }
    }
  }

  /**
   * Adds or removes labels on a resource in the default namespace.
   *
   * @param resource resource type, e.g. {@code node}
   * @param name resource name; {@code --all} applies to all nodes
   * @param keyValues labels to set; a key ending in {@code -} removes that label
   */
  public void label(String resource, String name, Tuple<?, ?>... keyValues) {
    label(resource, name, "", keyValues);
  }

  /**
   * Adds or removes labels on a resource.
   *
   * @param resource resource type, e.g. {@code node}
   * @param name resource name; {@code --all} applies to all nodes
   * @param namespace namespace of the resource; empty means the default namespace
   * @param keyValues labels to set; a key ending in {@code -} removes that label
   */
  public void label(String resource, String name, String namespace, Tuple<?, ?>... keyValues) {
    if (keyValues == null || keyValues.length == 0) {
      throw new IllegalArgumentException("Missing key-value-pairs");
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

    for (Tuple<?, ?> tuple : keyValues) {
      String key = String.valueOf(tuple.getFirst());
      String value = String.valueOf(tuple.getSecond());

      if (key.endsWith("-")) {
        labelsToRemove.add(key.substring(0, key.length() - 1));
      } else {
        labelsToAdd.put(key, value);
      }
    }

    executeWithErrorHandling(
        "label " + resource + "/" + name,
        () -> {
          Resource<? extends HasMetadata> resourceClient =
              K8sClientHelper.getResourceClient(
                  client, resource, name, resolveNamespace(namespace));
          applyLabelChanges(resourceClient, resource, name, labelsToAdd, labelsToRemove);
          return null;
        });

    log.debug("Labels updated successfully");
  }

  /**
   * Fetches the resource behind {@code resourceClient}, applies the given label additions/removals
   * and writes it back. Kept as a generic helper (rather than inline in {@link #label}) because
   * {@code io.fabric8.kubernetes.client.dsl.Resource#replace} requires the exact type returned by
   * {@code Resource#get}; a wildcard-typed local variable can't satisfy that across two separate
   * calls due to Java's per-expression wildcard capture, whereas a type variable bound once for the
   * whole method invocation can.
   */
  private static <T extends HasMetadata> void applyLabelChanges(
      Resource<T> resourceClient,
      String resource,
      String name,
      Map<String, String> labelsToAdd,
      List<String> labelsToRemove) {
    T existingResource = resourceClient.get();

    if (existingResource == null) {
      throw new IllegalStateException("Resource " + resource + "/" + name + " not found");
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
    resourceClient.patch(existingResource);
  }

  /**
   * Removes the given labels from a resource.
   *
   * @param resource resource type, e.g. {@code node}
   * @param name resource name; {@code --all} applies to all nodes
   * @param namespace namespace of the resource; empty means the default namespace
   * @param keys label keys to remove
   */
  public void labelRemove(String resource, String name, String namespace, String... keys) {
    Tuple<?, ?>[] tuples = new Tuple<?, ?>[keys.length];
    for (int i = 0; i < keys.length; i++) {
      tuples[i] = new Tuple<>(keys[i] + "-", "");
    }
    label(resource, name, namespace, tuples);
  }

  /**
   * Patches a resource in the default namespace using the default patch type.
   *
   * @param resource resource type, e.g. {@code service}
   * @param name resource name
   * @param yaml patch content as nested map
   */
  public void patch(String resource, String name, Map<String, Object> yaml) {
    patch(resource, name, "", "", yaml);
  }

  /**
   * Patches a resource using the default patch type.
   *
   * @param resource resource type, e.g. {@code service}
   * @param name resource name
   * @param namespace namespace of the resource; empty means the default namespace
   * @param yaml patch content as nested map
   */
  public void patch(String resource, String name, String namespace, Map<String, Object> yaml) {
    patch(resource, name, namespace, "", yaml);
  }

  /**
   * Patches a resource.
   *
   * @param resource resource type, e.g. {@code service}
   * @param name resource name
   * @param namespace namespace of the resource; empty means the default namespace
   * @param type patch type: {@code merge}, {@code json-merge}, {@code strategic} or {@code json}
   * @param yaml patch content as nested map
   */
  public void patch(
      String resource, String name, String namespace, String type, Map<String, Object> yaml) {
    log.debug("Patching {}/{} in namespace {}", resource, name, namespace);

    PatchContext patchContext = K8sClientHelper.createPatchContext(type);
    String patchJson = Serialization.asJson(yaml);
    log.trace("Patch JSON: {}", patchJson);

    executeWithErrorHandling(
        "patch " + resource + "/" + name,
        () -> {
          Resource<? extends HasMetadata> resourceClient =
              K8sClientHelper.getResourceClient(
                  client, resource, name, resolveNamespace(namespace));
          resourceClient.patch(patchContext, patchJson);
          return null;
        });

    log.debug("Resource {}/{} patched successfully", resource, name);
  }

  /**
   * Deletes resources by label selectors in the default namespace, see {@link #delete(String,
   * String, Tuple...)}.
   *
   * @param resource resource type, e.g. {@code secret}
   */
  public void delete(String resource) {
    delete(resource, "", new Tuple<?, ?>[0]);
  }

  /**
   * Deletes resources by label selectors, see {@link #delete(String, String, Tuple...)}.
   *
   * @param resource resource type, e.g. {@code secret}
   * @param namespace namespace to delete in; empty means the default namespace
   */
  public void delete(String resource, String namespace) {
    delete(resource, namespace, new Tuple<?, ?>[0]);
  }

  /**
   * Deletes all resources of a type matching the given label selectors. Failures are logged, not
   * thrown, since the resources may not exist.
   *
   * @param resource resource type, e.g. {@code secret}
   * @param namespace namespace to delete in; empty means the default namespace
   * @param selectors label key-value pairs the resources must match
   */
  public void delete(String resource, String namespace, Tuple<?, ?>... selectors) {
    if (selectors == null || selectors.length == 0) {
      throw new IllegalArgumentException("Missing selectors");
    }

    log.debug("Deleting {} in namespace {} with selectors", resource, namespace);

    Map<String, String> labels = new HashMap<>();
    for (Tuple<?, ?> tuple : selectors) {
      labels.put(String.valueOf(tuple.getFirst()), String.valueOf(tuple.getSecond()));
    }

    try {
      K8sClientHelper.deleteResourcesByType(client, resource, resolveNamespace(namespace), labels);
      log.debug("Resources deleted successfully");
    } catch (Exception e) {
      log.warn("Failed to delete resources (may not exist): {}", e.getMessage());
    }
  }

  /**
   * Deletes a single resource by name. Failures are logged, not thrown, since the resource may not
   * exist.
   *
   * @param resource resource type, e.g. {@code secret}
   * @param namespace namespace of the resource; empty means the default namespace
   * @param name resource name
   */
  public void delete(String resource, String namespace, String name) {
    log.debug("Deleting {}/{} in namespace {}", resource, name, namespace);

    try {
      Resource<? extends HasMetadata> resourceClient =
          K8sClientHelper.getResourceClient(client, resource, name, resolveNamespace(namespace));
      resourceClient.delete();
      log.debug("Resource {}/{} deleted successfully", resource, name);
    } catch (Exception e) {
      log.warn("Failed to delete resource (may not exist): {}", e.getMessage());
    }
  }

  /**
   * Runs a pod in the default namespace, see {@link #run(String, String, String, Map, String...)}.
   *
   * @param name name of the pod
   * @param image container image to run
   * @return a status message or, with {@code --rm}-style params, the pod output
   */
  public String run(String name, String image) {
    return run(name, image, "", Map.of(), new String[0]);
  }

  /**
   * Runs a pod, see {@link #run(String, String, String, Map, String...)}.
   *
   * @param name name of the pod
   * @param image container image to run
   * @param namespace target namespace; empty means the default namespace
   * @return a status message or, with {@code --rm}-style params, the pod output
   */
  public String run(String name, String image, String namespace) {
    return run(name, image, namespace, Map.of(), new String[0]);
  }

  /**
   * Runs a pod with pod-spec overrides, see {@link #run(String, String, String, Map, String...)}.
   *
   * @param name name of the pod
   * @param image container image to run
   * @param namespace target namespace; empty means the default namespace
   * @param overrides pod spec fields to override, analogous to {@code kubectl run --overrides}
   * @return a status message or, with {@code --rm}-style params, the pod output
   */
  public String run(String name, String image, String namespace, Map<String, ?> overrides) {
    return run(name, image, namespace, overrides, new String[0]);
  }

  /**
   * Runs a pod with kubectl-run-style params, see {@link #run(String, String, String, Map,
   * String...)}.
   *
   * @param name name of the pod
   * @param image container image to run
   * @param namespace target namespace; empty means the default namespace
   * @param params kubectl-run-style flags such as {@code --rm} or {@code --restart=Never}
   * @return a status message or, with {@code --rm}-style params, the pod output
   */
  public String run(String name, String image, String namespace, String... params) {
    return run(name, image, namespace, Map.of(), params);
  }

  /**
   * Runs a pod, analogous to {@code kubectl run}.
   *
   * @param name name of the pod
   * @param image container image to run
   * @param namespace target namespace; empty means the default namespace
   * @param overrides pod spec fields to override, analogous to {@code kubectl run --overrides}
   * @param params kubectl-run-style flags such as {@code --rm} or {@code --restart=Never}
   * @return a status message or, when the params request output collection, the pod output
   */
  public String run(
      String name, String image, String namespace, Map<String, ?> overrides, String... params) {
    log.debug("Running pod {} with image {} in namespace {}", name, image, namespace);
    String resolvedNamespace = resolveNamespace(namespace);
    List<String> runParams = params != null ? Arrays.asList(params) : Collections.emptyList();

    Pod pod =
        new PodBuilder()
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
    Pod createdPod =
        executeWithErrorHandling(
            "run pod " + name,
            () -> client.pods().inNamespace(resolvedNamespace).resource(finalPod).create());

    log.debug("Pod {} created successfully", name);
    if (K8sClientHelper.shouldReturnPodOutput(runParams)) {
      return K8sClientHelper.collectPodRunOutput(
          client,
          createdPod.getMetadata().getName(),
          resolvedNamespace,
          K8sClientHelper.shouldRemovePod(runParams),
          defaultRetries,
          sleepTimeMillis,
          this);
    }

    return "pod/" + createdPod.getMetadata().getName() + " created";
  }

  /**
   * Lists custom resources of the given type across all namespaces.
   *
   * @param resource custom resource type, resolved via API discovery
   * @return namespace/name pairs of all found resources; empty when the type is unknown or listing
   *     fails
   */
  public List<CustomResource> getCustomResource(String resource) {
    log.debug("Getting custom resources of type {}", resource);

    try {
      Map<String, Object> match =
          K8sClientHelper.findApiResourceViaDiscovery(
              client, resource.toLowerCase(Locale.ROOT), resource);
      if (match == null) {
        return Collections.emptyList();
      }
      ResourceDefinitionContext context =
          new ResourceDefinitionContext.Builder()
              .withGroup((String) match.get("group"))
              .withVersion((String) match.get("version"))
              .withKind((String) match.get("kind"))
              .withPlural((String) match.get("plural"))
              .withNamespaced((Boolean) match.get("namespaced"))
              .build();

      // `apiClient`'s type is a long nested generic (MixedOperation<GenericKubernetesResource,
      // GenericKubernetesResourceList, Resource<GenericKubernetesResource>>); spelling it out
      // would hurt readability more than `var` costs, so it's kept as `var` deliberately.
      var apiClient = client.genericKubernetesResources(context);
      GenericKubernetesResourceList resourceList = apiClient.inAnyNamespace().list();

      if (resourceList == null || resourceList.getItems() == null) {
        return Collections.emptyList();
      }

      return resourceList.getItems().stream().map(K8sClient::toCustomResource).toList();
    } catch (Exception e) {
      log.warn("Failed to get custom resources: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  private static CustomResource toCustomResource(GenericKubernetesResource item) {
    Map<String, Object> metadata =
        item.getMetadata() != null
            ? Serialization.unmarshal(Serialization.asJson(item.getMetadata()), MAP_TYPE)
            : Collections.emptyMap();
    String ns = metadata.containsKey("namespace") ? String.valueOf(metadata.get("namespace")) : "";
    String name = metadata.containsKey("name") ? String.valueOf(metadata.get("name")) : "";
    return new CustomResource(ns, name);
  }

  /**
   * Reads an annotation from a resource in the default namespace.
   *
   * @param resource resource type, e.g. {@code service}
   * @param name resource name
   * @param key annotation key to read
   * @return the annotation value; may be null when the annotation is not set
   */
  public String getAnnotation(String resource, String name, String key) {
    return getAnnotation(resource, name, key, "");
  }

  /**
   * Reads an annotation from a resource.
   *
   * @param resource resource type, e.g. {@code service}
   * @param name resource name
   * @param key annotation key to read
   * @param namespace namespace of the resource; empty means the default namespace
   * @return the annotation value; may be null when the annotation is not set
   */
  public String getAnnotation(String resource, String name, String key, String namespace) {
    log.debug("Getting annotation {} from {}/{} in namespace {}", key, resource, name, namespace);

    Resource<? extends HasMetadata> resourceClient =
        K8sClientHelper.getResourceClient(client, resource, name, resolveNamespace(namespace));
    HasMetadata k8sResource = resourceClient.get();

    if (k8sResource == null) {
      throw new IllegalStateException("Resource " + resource + "/" + name + " not found");
    }

    Map<String, String> annotations = k8sResource.getMetadata().getAnnotations();
    if (annotations == null) {
      throw new IllegalStateException("No annotations found on resource " + resource + "/" + name);
    }

    String value = annotations.get(key);
    log.debug("getAnnotation returns = {}", value);
    return value;
  }

  /**
   * Returns the name of the current kubeconfig context.
   *
   * @return the context name, or a placeholder when no context is set
   */
  public String getCurrentContext() {
    try {
      NamedContext currentContext = client.getConfiguration().getCurrentContext();
      String context = currentContext != null ? currentContext.getName() : null;
      return context != null ? context : "(current context not set)";
    } catch (Exception e) {
      log.trace("Failed to get current context: {}", e.getMessage());
      return "(current context not set)";
    }
  }

  /**
   * Waits for a resource to reach a phase using default timeout and check interval.
   *
   * @param resourceType resource type, e.g. {@code pod}
   * @param resourceName resource name
   * @param namespace namespace of the resource; empty means the default namespace
   * @param desiredPhase phase to wait for, e.g. {@code Running}
   */
  public void waitForResourcePhase(
      String resourceType, String resourceName, String namespace, String desiredPhase) {
    waitForResourcePhase(
        resourceType,
        resourceName,
        namespace,
        desiredPhase,
        DEFAULT_TIMEOUT_SECONDS,
        DEFAULT_CHECK_INTERVAL_SECONDS);
  }

  /**
   * Waits for a resource to reach a phase, polling in fixed intervals until the timeout expires.
   *
   * @param resourceType resource type, e.g. {@code pod}
   * @param resourceName resource name
   * @param namespace namespace of the resource; empty means the default namespace
   * @param desiredPhase phase to wait for, e.g. {@code Running}
   * @param timeoutSeconds maximum time to wait before failing
   * @param checkIntervalSeconds pause between phase checks
   */
  public void waitForResourcePhase(
      String resourceType,
      String resourceName,
      String namespace,
      String desiredPhase,
      int timeoutSeconds,
      int checkIntervalSeconds) {
    K8sClientHelper.validateWaitForResourcePhaseParams(
        resourceType, resourceName, namespace, desiredPhase, timeoutSeconds, checkIntervalSeconds);

    log.debug("Waiting for {}/{} to reach phase {}", resourceType, resourceName, desiredPhase);

    long startTime = System.currentTimeMillis();
    long endTime = startTime + ((long) timeoutSeconds * MILLIS_PER_SECOND);

    while (System.currentTimeMillis() < endTime) {
      if (hasReachedPhase(resourceType, resourceName, namespace, desiredPhase)) {
        log.debug(
            "Resource {}/{} in namespace {} reached the desired phase: {}",
            resourceType,
            resourceName,
            namespace,
            desiredPhase);
        return;
      }

      try {
        Thread.sleep((long) checkIntervalSeconds * MILLIS_PER_SECOND);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for resource phase", e);
      }
    }

    throw new IllegalStateException(
        "Timeout reached. Resource "
            + resourceType
            + "/"
            + resourceName
            + " in namespace "
            + namespace
            + " did not reach the desired phase: "
            + desiredPhase
            + " within "
            + timeoutSeconds
            + " seconds.");
  }

  private boolean hasReachedPhase(
      String resourceType, String resourceName, String namespace, String desiredPhase) {
    try {
      Resource<? extends HasMetadata> resourceClient =
          K8sClientHelper.getResourceClient(
              client, resourceType, resourceName, resolveNamespace(namespace));
      HasMetadata resource = resourceClient.get();
      if (resource == null) {
        return false;
      }

      String phase = extractPhase(resource);
      if (desiredPhase.equals(phase)) {
        return true;
      }

      log.debug("Current phase: {}. Waiting for phase: {}...", phase, desiredPhase);
      return false;
    } catch (Exception e) {
      log.trace("Error checking resource phase: {}", e.getMessage());
      return false;
    }
  }

  private static String extractPhase(HasMetadata resource) {
    if (resource instanceof Pod pod) {
      return pod.getStatus() != null ? pod.getStatus().getPhase() : null;
    }

    // Generic / Custom Resources
    Map<String, Object> status = Serialization.unmarshal(Serialization.asJson(resource), MAP_TYPE);
    Map<String, Object> statusMap = MapUtils.asStringObjectMap(status.get("status"));
    return statusMap != null ? (String) statusMap.get("phase") : null;
  }

  private <T> T waitForResourceWithRetry(String resourceDescription, Supplier<T> fetchSupplier) {
    int tryCount = 0;
    T result = null;

    while (result == null && tryCount < defaultRetries) {
      try {
        result = fetchSupplier.get();
      } catch (Exception e) {
        log.trace("Error fetching {}: {}", resourceDescription, e.getMessage());
      }

      if (result == null) {
        tryCount++;
        log.debug(
            "Still waiting for {}... (try {}/{})", resourceDescription, tryCount, defaultRetries);
        try {
          Thread.sleep(sleepTimeMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Interrupted while waiting", e);
        }
      }
    }

    if (result == null) {
      throw new IllegalStateException(
          "Failed to retrieve " + resourceDescription + " after " + defaultRetries + " retries");
    }

    return result;
  }

  private static <T> T executeWithErrorHandling(String operation, Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (Exception e) {
      throw new RuntimeException("Failed to " + operation + ": " + e.getMessage(), e);
    }
  }

  private static String resolveNamespace(String namespace) {
    return namespace != null && !namespace.isEmpty() ? namespace : DEFAULT_NAMESPACE;
  }

  /**
   * Returns the namespace the client currently operates in.
   *
   * @return the current namespace from the kubeconfig context
   */
  public String getCurrentNamespace() {
    return this.client.getNamespace();
  }

  private boolean runInOpenshift() {
    return this.gopConfig != null
        && this.gopConfig.getApplication() != null
        && this.gopConfig.getApplication().getOpenshift();
  }

  /**
   * Namespace/name coordinate of a custom resource as returned by {@link #getCustomResource}.
   *
   * @param namespace namespace the resource lives in; empty for cluster-scoped resources
   * @param name name of the resource
   */
  public record CustomResource(String namespace, String name) {}

  /** Thrown when a custom resource type cannot be resolved via Kubernetes API discovery. */
  public static class KubernetesApiResourceNotFoundException extends RuntimeException {
    /**
     * Creates the exception for the given unresolvable type.
     *
     * @param resourceType the custom resource type that could not be found
     */
    public KubernetesApiResourceNotFoundException(String resourceType) {
      super("No API resource found for custom resource type '" + resourceType + "'");
    }
  }
}
