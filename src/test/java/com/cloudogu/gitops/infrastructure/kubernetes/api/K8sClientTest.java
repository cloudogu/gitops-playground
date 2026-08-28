package com.cloudogu.gitops.infrastructure.kubernetes.api;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.utils.Tuple;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.openshift.api.model.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnableKubernetesMockClient
@SuppressWarnings("unchecked")
class K8sClientTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<Map<String, Object>>> JSON_LIST_TYPE = new TypeReference<>() {
	};

	KubernetesMockServer server;
	KubernetesClient client;

	private K8sClient k8sApiClient;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setup() {
		k8sApiClient = new K8sClient();
		k8sApiClient.setClient(client);
		k8sApiClient.sleepTimeMillis = 10; // Speed up tests
		k8sApiClient.defaultRetries = 3;
	}

	// ========================================
	// Node Operations Tests
	// ========================================

	@Test
	void waitForNodeReturnsFirstNodeName() {
		// Given
		var node = new NodeBuilder()
			.withNewMetadata()
			.withName("test-node-1")
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/nodes")
			  .andReturn(200, new NodeListBuilder().withItems(node).build())
			  .once();

		// When
		String nodeName = k8sApiClient.waitForNode();

		// Then
		assertThat(nodeName).isEqualTo("test-node-1");
	}

	@Test
	void waitForNodeRetriesWhenNoNodesAvailable() {
		// Given
		server.expect()
			  .get()
			  .withPath("/api/v1/nodes")
			  .andReturn(200, new NodeListBuilder().build())
			  .times(2);

		var node = new NodeBuilder()
			.withNewMetadata()
			.withName("test-node-1")
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/nodes")
			  .andReturn(200, new NodeListBuilder().withItems(node).build())
			  .once();

		// When
		String nodeName = k8sApiClient.waitForNode();

		// Then
		assertThat(nodeName).isEqualTo("test-node-1");
	}

	@Test
	void waitForNodeThrowsExceptionAfterMaxRetries() {
		// Given
		server.expect()
			  .get()
			  .withPath("/api/v1/nodes")
			  .andReturn(200, new NodeListBuilder().build())
			  .times(k8sApiClient.defaultRetries + 1);

		// When/Then
		var exception = assertThrows(RuntimeException.class, () -> k8sApiClient.waitForNode());
		assertThat(exception.getMessage()).contains("Failed to retrieve node");
	}

	@Test
	void waitForInternalNodeIpReturnsNodeInternalIP() {
		// Given - First call for waitForNode
		var node = new NodeBuilder()
			.withNewMetadata()
			.withName("test-node-1")
			.endMetadata()
			.withNewStatus()
			.addNewAddress()
			.withType("InternalIP")
			.withAddress("192.168.1.100")
			.endAddress()
			.endStatus()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/nodes")
			  .andReturn(200, new NodeListBuilder().withItems(node).build())
			  .once();

		// Second call for waitForInternalNodeIp
		server.expect()
			  .get()
			  .withPath("/api/v1/nodes/test-node-1")
			  .andReturn(200, node)
			  .once();

		// When
		String ip = k8sApiClient.waitForInternalNodeIp();

		// Then
		assertThat(ip).isEqualTo("192.168.1.100");
	}

	@Test
	void waitForInternalNodeIpIgnoresIPv6Addresses() {
		// Given
		var node = new NodeBuilder()
			.withNewMetadata()
			.withName("test-node-1")
			.endMetadata()
			.withNewStatus()
			.addNewAddress()
			.withType("InternalIP")
			.withAddress("192.168.1.100")
			.endAddress()
			.addNewAddress()
			.withType("InternalIP")
			.withAddress("fe80::1")
			.endAddress()
			.endStatus()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/nodes")
			  .andReturn(200, new NodeListBuilder().withItems(node).build())
			  .once();

		server.expect()
			  .get()
			  .withPath("/api/v1/nodes/test-node-1")
			  .andReturn(200, node)
			  .once();

		// When
		String ip = k8sApiClient.waitForInternalNodeIp();

		// Then
		assertThat(ip).isEqualTo("192.168.1.100");
	}

	// ========================================
	// Service Operations Tests
	// ========================================

	@Test
	void waitForNodePortReturnsServiceNodePort() {
		// Given
		var service = new ServiceBuilder()
			.withNewMetadata()
			.withName("test-service")
			.withNamespace("test-ns")
			.endMetadata()
			.withNewSpec()
			.addNewPort()
			.withPort(8080)
			.withNodePort(30080)
			.endPort()
			.endSpec()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/services/test-service")
			  .andReturn(200, service)
			  .once();

		// When
		String nodePort = k8sApiClient.waitForNodePort("test-service", "test-ns");

		// Then
		assertThat(nodePort).isEqualTo("30080");
	}

	@Test
	void createServiceNodePortCreatesServiceWithNodePort() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/default/services")
			  .andReturn(
				  201, new ServiceBuilder()
					  .withNewMetadata()
					  .withName("my-service")
					  .withNamespace("default")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createServiceNodePort("my-service", "8080:80", "30000", "");

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/default/services");

		Map<String, Object> body = parseJson(request.getUtf8Body());
		Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");
		Map<String, Object> spec = (Map<String, Object>) body.get("spec");
		List<Map<String, Object>> ports = (List<Map<String, Object>>) spec.get("ports");

		assertThat(metadata.get("name")).isEqualTo("my-service");
		assertThat(metadata.get("namespace")).isEqualTo("default");
		assertThat(spec.get("type")).isEqualTo("NodePort");
		assertThat(ports).hasSize(1);
		assertThat(ports.get(0).get("port")).isEqualTo(8080);
		assertThat(ports.get(0).get("targetPort")).isEqualTo(80);
		assertThat(ports.get(0).get("nodePort")).isEqualTo(30000);
	}

	@Test
	void createServiceNodePortCreatesServiceWithoutExplicitNodePort() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/test-ns/services")
			  .andReturn(
				  201, new ServiceBuilder()
					  .withNewMetadata()
					  .withName("my-service")
					  .withNamespace("test-ns")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createServiceNodePort("my-service", "8080:80", "", "test-ns");

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/test-ns/services");

		Map<String, Object> body = parseJson(request.getUtf8Body());
		Map<String, Object> spec = (Map<String, Object>) body.get("spec");
		List<Map<String, Object>> ports = (List<Map<String, Object>>) spec.get("ports");

		assertThat(spec.get("type")).isEqualTo("NodePort");
		assertThat(ports).hasSize(1);
		assertThat(ports.get(0).get("port")).isEqualTo(8080);
		assertThat(ports.get(0).get("targetPort")).isEqualTo(80);
		assertThat(ports.get(0).get("nodePort")).isNull();
	}

	@Test
	void patchServiceNodePortUpdatesServicePort() throws InterruptedException {
		// Given
		var service = new ServiceBuilder()
			.withNewMetadata()
			.withName("test-service")
			.withNamespace("test-ns")
			.endMetadata()
			.withNewSpec()
			.addNewPort()
			.withName("http")
			.withPort(8080)
			.withNodePort(30080)
			.endPort()
			.endSpec()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/services/test-service")
			  .andReturn(200, service)
			  .once();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/services/test-service")
			  .andReturn(200, service)
			  .once();

		server.expect()
			  .patch()
			  .withPath("/api/v1/namespaces/test-ns/services/test-service")
			  .andReturn(200, service)
			  .once();

		// When
		k8sApiClient.patchServiceNodePort("test-service", "test-ns", "http", 30090);

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("PATCH");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/test-ns/services/test-service");
		assertThat(parseJsonList(request.getUtf8Body())).containsExactly(Map.of(
			"op", "replace",
			"path", "/spec/ports/0/nodePort",
			"value", 30090
		));
	}

	@Test
	void patchServiceNodePortThrowsExceptionForInvalidParameters() {
		// When/Then
		var exception = assertThrows(
			IllegalArgumentException.class,
			() -> k8sApiClient.patchServiceNodePort("", "test-ns", "http", 30000)
		);
		assertThat(exception.getMessage()).contains("Service name");
	}

	@Test
	void patchServiceNodePortThrowsExceptionWhenPortNotFound() {
		// Given
		var service = new ServiceBuilder()
			.withNewMetadata()
			.withName("test-service")
			.withNamespace("test-ns")
			.endMetadata()
			.withNewSpec()
			.addNewPort()
			.withName("http")
			.withPort(8080)
			.endPort()
			.endSpec()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/services/test-service")
			  .andReturn(200, service)
			  .once();

		// When/Then
		var exception = assertThrows(
			RuntimeException.class,
			() -> k8sApiClient.patchServiceNodePort("test-service", "test-ns", "https", 30000)
		);
		assertThat(exception.getMessage()).contains("Port with name https not found");
	}

	// ========================================
	// Namespace Operations Tests
	// ========================================

	@Test
	void createNamespaceCreatesNewNamespace() throws InterruptedException {
		// Given
		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns")
			  .andReturn(404, "")
			  .once();
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces")
			  .andReturn(
				  201, new NamespaceBuilder()
					  .withNewMetadata()
					  .withName("test-ns")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createNamespace("test-ns");

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces");
		Map<String, Object> body = parseJson(request.getUtf8Body());
		assertThat(body.get("kind")).isEqualTo("Namespace");
		assertThat(((Map<String, Object>) body.get("metadata")).get("name")).isEqualTo("test-ns");
	}

	@Test
	void createNamespaceCreatesOpenShiftProjectWhenOpenshiftConfigIsEnabled() throws InterruptedException {
		// Given
		Config config = Config.fromMap(Map.of("application", Map.of("openshift", true)));
		k8sApiClient.setGopConfig(config);

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-project")
			  .andReturn(404, "")
			  .once();

		server.expect()
			  .post()
			  .withPath("/apis/project.openshift.io/v1/projects")
			  .andReturn(
				  201, new ProjectBuilder()
					  .withNewMetadata()
					  .withName("test-project")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createNamespace("test-project");

		// Then
		Map<String, Object> requestBody = parseJson(server.getLastRequest().getUtf8Body());
		assertThat(requestBody.get("kind")).isEqualTo("Project");
		assertThat(((Map<String, Object>) requestBody.get("metadata")).get("name")).isEqualTo("test-project");
	}

	@Test
	void createNamespaceCreatesKubernetesNamespaceWhenOpenshiftConfigIsDisabled() throws InterruptedException {
		// Given
		Config config = Config.fromMap(Map.of("application", Map.of("openshift", false)));
		k8sApiClient.setGopConfig(config);

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns")
			  .andReturn(404, "")
			  .once();

		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces")
			  .andReturn(
				  201, new NamespaceBuilder()
					  .withNewMetadata()
					  .withName("test-ns")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createNamespace("test-ns");

		// Then
		Map<String, Object> requestBody = parseJson(server.getLastRequest().getUtf8Body());
		assertThat(requestBody.get("kind")).isEqualTo("Namespace");
		assertThat(((Map<String, Object>) requestBody.get("metadata")).get("name")).isEqualTo("test-ns");
	}

	@Test
	void createNamespaceDoesNotCreateExistingNamespace() throws InterruptedException {
		// Given
		var namespace = new NamespaceBuilder()
			.withNewMetadata()
			.withName("test-ns")
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns")
			  .andReturn(200, namespace)
			  .once();

		// When
		k8sApiClient.createNamespace("test-ns");

		// Then
		assertThat(server.getLastRequest().getMethod()).isEqualTo("GET");
		assertThat(server.getLastRequest().getPath()).isEqualTo("/api/v1/namespaces/test-ns");
	}

	@Test
	void createNamespaceCreatesKubernetesNamespaceWhenConfigIsNull() throws InterruptedException {
		// Given
		k8sApiClient.setGopConfig(null);

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns")
			  .andReturn(404, "")
			  .once();

		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces")
			  .andReturn(
				  201, new NamespaceBuilder()
					  .withNewMetadata()
					  .withName("test-ns")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createNamespace("test-ns");

		// Then
		Map<String, Object> requestBody = parseJson(server.getLastRequest().getUtf8Body());
		assertThat(requestBody.get("kind")).isEqualTo("Namespace");
		assertThat(((Map<String, Object>) requestBody.get("metadata")).get("name")).isEqualTo("test-ns");
	}

	@Test
	void createNamespaceDoesNotCreateOpenShiftProjectWhenNamespaceAlreadyExists() throws InterruptedException {
		// Given
		Config config = Config.fromMap(Map.of("application", Map.of("openshift", true)));
		k8sApiClient.setGopConfig(config);

		var namespace = new NamespaceBuilder()
			.withNewMetadata()
			.withName("existing-project")
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/existing-project")
			  .andReturn(200, namespace)
			  .once();

		// When
		k8sApiClient.createNamespace("existing-project");

		// Then
		assertThat(server.getLastRequest().getMethod()).isEqualTo("GET");
		assertThat(server.getLastRequest().getPath()).isEqualTo("/api/v1/namespaces/existing-project");
	}

	@Test
	void createNamespaceThrowsExceptionForInvalidName() {
		// When/Then
		var exception = assertThrows(IllegalArgumentException.class, () -> k8sApiClient.createNamespace(""));
		assertThat(exception.getMessage()).contains("Namespace name must be provided");
	}

	@Test
	void createNamespacesCreatesMultipleNamespaces() throws InterruptedException {
		// Given
		server.expect().get().withPath("/api/v1/namespaces/ns1").andReturn(404, "").once();
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces")
			  .andReturn(201, new NamespaceBuilder().withNewMetadata().withName("ns1").endMetadata().build())
			  .once();
		server.expect().get().withPath("/api/v1/namespaces/ns2").andReturn(404, "").once();
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces")
			  .andReturn(201, new NamespaceBuilder().withNewMetadata().withName("ns2").endMetadata().build())
			  .once();

		// When
		k8sApiClient.createNamespaces(List.of("ns1", "ns2"));

		// Then
		assertThat(server.getRequestCount()).isEqualTo(4);
		assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/namespaces/ns1");
		Map<String, Object> firstCreate = parseJson(server.takeRequest().getUtf8Body());
		assertThat(((Map<String, Object>) firstCreate.get("metadata")).get("name")).isEqualTo("ns1");
		assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/namespaces/ns2");
		Map<String, Object> secondCreate = parseJson(server.takeRequest().getUtf8Body());
		assertThat(((Map<String, Object>) secondCreate.get("metadata")).get("name")).isEqualTo("ns2");
	}

	@Test
	void namespaceExistsReturnsTrueForExistingNamespace() {
		// Given
		var namespace = new NamespaceBuilder()
			.withNewMetadata()
			.withName("test-ns")
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns")
			  .andReturn(200, namespace)
			  .once();

		// When
		boolean exists = k8sApiClient.namespaceExists("test-ns");

		// Then
		assertThat(exists).isTrue();
	}

	@Test
	void namespaceExistsReturnsFalseForNonExistingNamespace() {
		// Given
		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/non-existing")
			  .andReturn(404, "")
			  .once();

		// When
		boolean exists = k8sApiClient.namespaceExists("non-existing");

		// Then
		assertThat(exists).isFalse();
	}

	// ========================================
	// Secret Operations Tests
	// ========================================

	@Test
	void createSecretCreatesGenericSecret() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/test-ns/secrets")
			  .andReturn(
				  201, new SecretBuilder()
					  .withNewMetadata()
					  .withName("my-secret")
					  .withNamespace("test-ns")
					  .endMetadata()
					  .withType("Opaque")
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createSecret(
			"Opaque", "my-secret", "test-ns",
			new Tuple("username", "admin"),
			new Tuple("password", "secret")
		);

		// Then
		var request = server.getLastRequest();
		Map<String, Object> body = parseJson(request.getUtf8Body());
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/test-ns/secrets");
		assertThat(body.get("type")).isEqualTo("Opaque");
		assertThat(((Map<String, Object>) body.get("metadata")).get("name")).isEqualTo("my-secret");
		assertThat(((Map<String, Object>) body.get("stringData")))
			.containsEntry("username", "admin")
			.containsEntry("password", "secret");
	}

	@Test
	void createSecretUpdatesAnExistingSecretWithoutDeletingIt() throws InterruptedException {
		var secret = new SecretBuilder()
			.withNewMetadata()
			.withName("my-secret")
			.withNamespace("test-ns")
			.endMetadata()
			.withType("Opaque")
			.build();

		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/test-ns/secrets")
			  .andReturn(409, new StatusBuilder().withCode(409).build())
			  .once();
		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/secrets/my-secret")
			  .andReturn(200, secret)
			  .once();
		server.expect()
			  .put()
			  .withPath("/api/v1/namespaces/test-ns/secrets/my-secret")
			  .andReturn(200, secret)
			  .once();

		k8sApiClient.createSecret("Opaque", "my-secret", "test-ns", new Tuple("username", "admin"));

		assertThat(server.getRequestCount()).isEqualTo(3);
		assertThat(server.takeRequest().getMethod()).isEqualTo("POST");
		assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
		var updateRequest = server.takeRequest();
		assertThat(updateRequest.getMethod()).isEqualTo("PUT");
		assertThat(updateRequest.getPath()).isEqualTo("/api/v1/namespaces/test-ns/secrets/my-secret");
		assertThat(updateRequest.getUtf8Body()).contains("\"username\":\"admin\"");
	}

	@Test
	void createImagePullSecretCreatesDockerRegistrySecret() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/default/secrets")
			  .andReturn(
				  201, new SecretBuilder()
					  .withNewMetadata()
					  .withName("my-registry")
					  .withNamespace("default")
					  .endMetadata()
					  .withType("kubernetes.io/dockerconfigjson")
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createImagePullSecret("my-registry", "", "docker.io", "user\"name", "pa\"ss");

		Map<String, Object> requestBody = parseJson(server.getLastRequest().getUtf8Body());
		Map<String, Object> dockerConfig = parseJson((String) ((Map<String, Object>) requestBody.get("stringData")).get(
			".dockerconfigjson"));
		assertThat(((Map<String, Object>) ((Map<String, Object>) dockerConfig.get("auths")).get("docker.io")).get(
			"username")).isEqualTo("user\"name");
		assertThat(((Map<String, Object>) ((Map<String, Object>) dockerConfig.get("auths")).get("docker.io")).get(
			"password")).isEqualTo("pa\"ss");
	}

	@Test
	void getArgoCDNamespacesSecretRetrievesSecretData() {
		// Given
		var secret = new SecretBuilder()
			.withNewMetadata()
			.withName("argocd-secret")
			.withNamespace("argocd")
			.endMetadata()
			.withData(Map.of(
				"namespaces",
				Base64.getEncoder().encodeToString("ns1,ns2".getBytes(StandardCharsets.UTF_8))
			))
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/argocd/secrets/argocd-secret")
			  .andReturn(200, secret)
			  .once();

		// When
		String data = k8sApiClient.getArgoCDNamespacesSecret("argocd-secret", "argocd");

		// Then
		assertThat(data).isEqualTo(Base64.getEncoder().encodeToString("ns1,ns2".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void getCredentialsFromSecretExtractsUsernameAndPassword() {
		// Given
		var secret = new SecretBuilder()
			.withNewMetadata()
			.withName("my-secret")
			.withNamespace("test-ns")
			.endMetadata()
			.withData(Map.of(
				"username", Base64.getEncoder().encodeToString("admin".getBytes(StandardCharsets.UTF_8)),
				"password", Base64.getEncoder().encodeToString("secret123".getBytes(StandardCharsets.UTF_8))
			))
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/secrets/my-secret")
			  .andReturn(200, secret)
			  .once();

		// When
		Credentials creds = k8sApiClient.getCredentialsFromSecret("my-secret", "test-ns");

		// Then
		assertThat(creds.getUsername()).isEqualTo("admin");
		assertThat(creds.getPassword()).isEqualTo("secret123");
	}

	@Test
	void getCredentialsFromSecretWithCredentialsObject() {
		// Given
		var inputCreds = new Credentials();
		inputCreds.setSecretName("my-secret");
		inputCreds.setSecretNamespace("test-ns");
		inputCreds.setUsernameKey("user");
		inputCreds.setPasswordKey("pass");

		var secret = new SecretBuilder()
			.withNewMetadata()
			.withName("my-secret")
			.withNamespace("test-ns")
			.endMetadata()
			.withData(Map.of(
				"user", Base64.getEncoder().encodeToString("testuser".getBytes(StandardCharsets.UTF_8)),
				"pass", Base64.getEncoder().encodeToString("testpass".getBytes(StandardCharsets.UTF_8))
			))
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/secrets/my-secret")
			  .andReturn(200, secret)
			  .once();

		// When
		Credentials result = k8sApiClient.getCredentialsFromSecret(inputCreds);

		// Then
		assertThat(result.getUsername()).isEqualTo("testuser");
		assertThat(result.getPassword()).isEqualTo("testpass");
	}

	// ========================================
	// ConfigMap Operations Tests
	// ========================================

	@Test
	void createConfigMapFromFileCreatesConfigmap() throws IOException, InterruptedException {
		// Given
		Path testFile = tempDir.resolve("test.txt");
		Files.writeString(testFile, "test content");

		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/default/configmaps")
			  .andReturn(
				  201, new ConfigMapBuilder()
					  .withNewMetadata()
					  .withName("my-config")
					  .withNamespace("default")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createConfigMapFromFile("my-config", "", testFile.toString());

		// Then
		var request = server.getLastRequest();
		Map<String, Object> body = parseJson(request.getUtf8Body());
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/default/configmaps");
		assertThat(((Map<String, Object>) body.get("metadata")).get("name")).isEqualTo("my-config");
		assertThat(((Map<String, Object>) body.get("data"))).containsEntry("test.txt", "test content");
	}

	@Test
	void createConfigMapFromFileThrowsExceptionForNonExistingFile() {
		// When/Then
		var exception = assertThrows(
			RuntimeException.class,
			() -> k8sApiClient.createConfigMapFromFile("my-config", "", "/non/existing/file.txt")
		);
		assertThat(exception.getMessage()).contains("File not found");
	}

	@Test
	void getConfigMapRetrievesValueFromConfigmap() {
		// Given
		var configMap = new ConfigMapBuilder()
			.withNewMetadata()
			.withName("my-config")
			.withNamespace("test")
			.endMetadata()
			.withData(Map.of("key1", "value1", "key2", "value2"))
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test/configmaps/my-config")
			  .andReturn(200, configMap)
			  .once();

		// When
		String value = k8sApiClient.getConfigMap("my-config", "key1");

		// Then
		assertThat(value).isEqualTo("value1");
	}

	@Test
	void getConfigMapThrowsExceptionForNonExistingKey() {
		// Given
		var configMap = new ConfigMapBuilder()
			.withNewMetadata()
			.withName("my-config")
			.withNamespace("test")
			.endMetadata()
			.withData(Map.of("key1", "value1"))
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test/configmaps/my-config")
			  .andReturn(200, configMap)
			  .once();

		// When/Then
		var exception = assertThrows(
			RuntimeException.class,
			() -> k8sApiClient.getConfigMap("my-config", "non-existing-key")
		);
		assertThat(exception.getMessage()).contains("Could not fetch non-existing-key");
	}

	// ========================================
	// Resource Management Tests
	// ========================================

	@Test
	void applyYamlAppliesResourcesFromFile() throws IOException {
		// Given
		Path yamlFile = tempDir.resolve("test.yaml");
		Files.writeString(
			yamlFile, """
				apiVersion: v1
				kind: Namespace
				metadata:
				  name: test-ns
				"""
		);

		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces")
			  .andReturn(
				  201, new NamespaceBuilder()
					  .withNewMetadata()
					  .withName("test-ns")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		String result = k8sApiClient.applyYaml(yamlFile.toString());

		// Then
		assertThat(result).contains("Applied 1 resource(s)");
	}

	@Test
	void applyYamlThrowsExceptionForNonExistingFileOrDirectory() {
		// When/Then
		var exception = assertThrows(RuntimeException.class, () -> k8sApiClient.applyYaml("/non/existing/file.yaml"));

		assertThat(exception.getMessage()).contains("File or directory not found");
		assertThat(exception.getMessage()).contains("/non/existing/file.yaml");
	}

	@Test
	void labelAddsLabelsToResource() throws InterruptedException {
		// Given
		var pod = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("default")
			.withLabels(Map.of("existing", "label"))
			.endMetadata()
			.build();

		server.expect().get().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();
		server.expect().get().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();
		server.expect().patch().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();

		// When
		k8sApiClient.label(
			"pod", "test-pod", "default",
			new Tuple("app", "myapp"),
			new Tuple("version", "1.0")
		);

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("PATCH");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/default/pods/test-pod");
		assertThat(parseJsonList(request.getUtf8Body())).containsExactlyInAnyOrder(
			Map.of("op", "add", "path", "/metadata/labels/app", "value", "myapp"),
			Map.of("op", "add", "path", "/metadata/labels/version", "value", "1.0")
		);
	}

	@Test
	void labelRemoveRemovesLabelsFromResource() throws InterruptedException {
		// Given
		var pod = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("default")
			.withLabels(Map.of("app", "myapp", "version", "1.0"))
			.endMetadata()
			.build();

		server.expect().get().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();
		server.expect().get().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();
		server.expect().patch().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();

		// When
		k8sApiClient.labelRemove("pod", "test-pod", "default", "version");

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("PATCH");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/default/pods/test-pod");
		assertThat(parseJsonList(request.getUtf8Body())).containsExactly(
			Map.of("op", "remove", "path", "/metadata/labels/version")
		);
	}

	@Test
	void patchPatchesResourceWithStrategicMerge() throws InterruptedException {
		// Given
		var pod = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("default")
			.endMetadata()
			.build();

		server.expect().get().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();
		server.expect().patch().withPath("/api/v1/namespaces/default/pods/test-pod").andReturn(200, pod).once();

		// When
		k8sApiClient.patch(
			"pod", "test-pod", "default", "strategic", Map.of(
				"metadata", Map.of("labels", Map.of("new", "label")))
		);

		// Then
		var request = server.getLastRequest();
		Map<String, Object> body = parseJson(request.getUtf8Body());
		assertThat(request.getMethod()).isEqualTo("PATCH");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/default/pods/test-pod");
		assertThat((Map<String, Object>) ((Map<String, Object>) body.get("metadata")).get("labels"))
			.containsEntry("new", "label");
	}

	@Test
	void patchRejectsAnUnknownPatchType() {
		var exception = assertThrows(
			IllegalArgumentException.class,
			() -> k8sApiClient.patch("pod", "test-pod", "default", "unknown", Map.of())
		);

		assertThat(exception.getMessage()).isEqualTo("Unsupported patch type: unknown");
	}

	@Test
	void deleteRemovesResourcesByLabelSelector() throws InterruptedException {
		// Given
		server.expect()
			  .delete()
			  .withPath("/api/v1/namespaces/test-ns/pods?labelSelector=app%3Dmyapp")
			  .andReturn(200, new StatusBuilder().build())
			  .once();

		// When
		k8sApiClient.delete("pod", "test-ns", new Tuple("app", "myapp"));

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("DELETE");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/test-ns/pods?labelSelector=app%3Dmyapp");
	}

	@Test
	void deleteWithoutSelectorsRemovesAllResourcesOfTheType() throws InterruptedException {
		server.expect()
			  .delete()
			  .withPath("/api/v1/namespaces/test-ns/pods")
			  .andReturn(200, new StatusBuilder().build())
			  .once();

		k8sApiClient.delete("pod", "test-ns");

		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("DELETE");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/test-ns/pods");
	}

	@Test
	void deleteRemovesSpecificResourceByName() throws InterruptedException {
		// Given
		server.expect()
			  .delete()
			  .withPath("/api/v1/namespaces/test-ns/pods/test-pod")
			  .andReturn(200, new StatusBuilder().build())
			  .once();

		// When
		k8sApiClient.delete("pod", "test-ns", "test-pod");

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("DELETE");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/test-ns/pods/test-pod");
	}

	@Test
	void runCreatesPodWithImage() {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/default/pods")
			  .andReturn(
				  201, new PodBuilder()
					  .withNewMetadata()
					  .withName("test-pod")
					  .endMetadata()
					  .build()
			  )
			  .once();

		// When
		String result = k8sApiClient.run("test-pod", "nginx:latest", "", Map.of());

		// Then
		assertThat(result).contains("pod/test-pod created");
	}

	@Test
	void runAppliesPodOverridesInsteadOfGeneratedParameterValues() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/jenkins/pods")
			  .andReturn(
				  201, new PodBuilder()
					  .withNewMetadata()
					  .withName("test-pod")
					  .endMetadata()
					  .build()
			  )
			  .once();

		String overrideImage = "bash:42";
		Map<String, Object> overrides = Map.of(
			"spec", Map.of(
				"containers", List.of(Map.of(
					"name", "override-container",
					"image", overrideImage,
					"args", List.of("cat", "/etc/group"),
					"volumeMounts", List.of(Map.of("name", "group", "mountPath", "/etc/group", "readOnly", true))
				)),
				"nodeSelector", Map.of("node", "jenkins"),
				"volumes", List.of(Map.of("name", "group", "hostPath", Map.of("path", "/etc/group")))
			)
		);

		// When
		k8sApiClient.run("test-pod", "nginx:latest", "jenkins", overrides);

		// Then
		Map<String, Object> requestBody = parseJson(server.getLastRequest().getUtf8Body());
		assertThat(((Map<String, Object>) requestBody.get("metadata")).get("name")).isEqualTo("test-pod");
		assertThat(((Map<String, Object>) requestBody.get("metadata")).get("namespace")).isEqualTo("jenkins");
		assertThat(((Map<String, Object>) ((Map<String, Object>) requestBody.get("spec")).get("nodeSelector")).get(
			"node")).isEqualTo("jenkins");

		List<Map<String, Object>> containers = (List<Map<String, Object>>) ((Map<String, Object>) requestBody.get("spec")).get(
			"containers");
		assertThat(containers).hasSize(1);
		Map<String, Object> container = containers.get(0);
		assertThat(container.get("name")).isEqualTo("override-container");
		assertThat(container.get("image")).isEqualTo("bash:42");
		List<String> args = (List<String>) container.get("args");
		assertThat(args).containsExactly("cat", "/etc/group");

		List<Map<String, Object>> volumeMounts = (List<Map<String, Object>>) container.get("volumeMounts");
		Map<String, Object> volumeMount = volumeMounts.get(0);
		assertThat(volumeMount.get("mountPath")).isEqualTo("/etc/group");
		assertThat(volumeMount.get("readOnly")).isEqualTo(true);

		List<Map<String, Object>> volumes = (List<Map<String, Object>>) ((Map<String, Object>) requestBody.get("spec")).get(
			"volumes");
		Map<String, Object> volume = volumes.get(0);
		assertThat(((Map<String, Object>) volume.get("hostPath")).get("path")).isEqualTo("/etc/group");
	}

	@Test
	void runReturnsPodLogsAndRemovesPodForInteractiveRmMode() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/jenkins/pods")
			  .andReturn(
				  201, new PodBuilder()
					  .withNewMetadata()
					  .withName("gid-pod")
					  .endMetadata()
					  .build()
			  )
			  .once();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/jenkins/pods/gid-pod")
			  .andReturn(
				  200, new PodBuilder()
					  .withNewMetadata()
					  .withName("gid-pod")
					  .endMetadata()
					  .withNewStatus()
					  .withPhase("Succeeded")
					  .endStatus()
					  .build()
			  )
			  .once();

		var succeededPod = new PodBuilder()
			.withNewMetadata()
			.withName("gid-pod")
			.endMetadata()
			.withNewStatus()
			.withPhase("Succeeded")
			.endStatus()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/jenkins/pods?fieldSelector=metadata.name%3Dgid-pod")
			  .andReturn(200, new PodListBuilder().withItems(succeededPod).build())
			  .once();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/jenkins/pods/gid-pod/log?pretty=false")
			  .andReturn(200, "root:x:0:\ndocker:x:42:\n")
			  .once();

		server.expect()
			  .delete()
			  .withPath("/api/v1/namespaces/jenkins/pods/gid-pod")
			  .andReturn(200, new StatusBuilder().build())
			  .once();

		// When
		String result = k8sApiClient.run("gid-pod", "bash:42", "jenkins", "--restart=Never", "-ti", "--rm", "--quiet");

		// Then
		assertThat(result).isEqualTo("root:x:0:\ndocker:x:42:\n");

		Map<String, Object> createRequest = parseJson(server.takeRequest().getUtf8Body());
		assertThat(((Map<String, Object>) createRequest.get("spec")).get("restartPolicy")).isEqualTo("Never");
	}

	// ========================================
	// Query Operations Tests
	// ========================================

	@Test
	void getCustomResourceReturnsListOfCustomResources() {
		// Given
		server.expect()
			  .get()
			  .withPath("/apis")
			  .andReturn(
				  200, Map.of(
					  "groups", List.of(Map.of(
						  "name", "example.io",
						  "preferredVersion", Map.of("version", "v1"),
						  "versions", List.of(Map.of("version", "v1"))
					  ))
				  )
			  )
			  .once();
		server.expect()
			  .get()
			  .withPath("/apis/example.io/v1")
			  .andReturn(
				  200, Map.of(
					  "resources", List.of(Map.of(
						  "name", "widgets",
						  "singularName", "widget",
						  "namespaced", true,
						  "kind", "Widget",
						  "shortNames", List.of()
					  ))
				  )
			  )
			  .once();
		server.expect()
			  .get()
			  .withPath("/apis/example.io/v1/widgets")
			  .andReturn(
				  200, Map.of(
					  "apiVersion", "example.io/v1",
					  "kind", "WidgetList",
					  "items", List.of(
						  Map.of(
							  "apiVersion",
							  "example.io/v1",
							  "kind",
							  "Widget",
							  "metadata",
							  Map.of("namespace", "ns-a", "name", "widget-a")
						  ),
						  Map.of(
							  "apiVersion",
							  "example.io/v1",
							  "kind",
							  "Widget",
							  "metadata",
							  Map.of("namespace", "ns-b", "name", "widget-b")
						  )
					  )
				  )
			  )
			  .once();

		// When
		List<K8sClient.CustomResource> result = k8sApiClient.getCustomResource("widget");

		// Then
		assertThat(result).containsExactly(
			new K8sClient.CustomResource("ns-a", "widget-a"),
			new K8sClient.CustomResource("ns-b", "widget-b")
		);
	}

	@Test
	void getAnnotationRetrievesAnnotationValue() {
		// Given
		var pod = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("default")
			.withAnnotations(Map.of("key1", "value1", "key2", "value2"))
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/default/pods/test-pod")
			  .andReturn(200, pod)
			  .once();

		// When
		String value = k8sApiClient.getAnnotation("pod", "test-pod", "key1", "default");

		// Then
		assertThat(value).isEqualTo("value1");
	}

	@Test
	void getAnnotationReturnsNullForNonExistingAnnotation() {
		// Given
		var pod = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("default")
			.withAnnotations(Map.of("key1", "value1"))
			.endMetadata()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/default/pods/test-pod")
			  .andReturn(200, pod)
			  .once();

		// When
		String value = k8sApiClient.getAnnotation("pod", "test-pod", "non-existing", "default");

		// Then
		assertThat(value).isNull();
	}

	@Test
	void getCurrentContextReturnsContextName() {
		// When
		String context = k8sApiClient.getCurrentContext();

		// Then
		assertThat(context).isNotNull();
		// Note: Actual value depends on mock client configuration
	}

	// ========================================
	// Wait Operations Tests
	// ========================================

	@Test
	void waitForResourcePhaseWaitsForPodToReachRunningPhase() {
		// Given
		var podRunning = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("test-ns")
			.endMetadata()
			.withNewStatus()
			.withPhase("Running")
			.endStatus()
			.build();
		server.expect().get().withPath("/api/v1/namespaces/test-ns/pods/test-pod").andReturn(200, podRunning).once();

		// When
		k8sApiClient.waitForResourcePhase("pod", "test-pod", "test-ns", "Running", 5, 1);

		// Then
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void waitForResourcePhaseRetriesUntilPhaseIsReached() {
		// Given
		var podPending = new PodBuilder()
			.withNewMetadata().withName("test-pod").withNamespace("test-ns").endMetadata()
			.withNewStatus().withPhase("Pending").endStatus()
			.build();
		var podRunning = new PodBuilder()
			.withNewMetadata().withName("test-pod").withNamespace("test-ns").endMetadata()
			.withNewStatus().withPhase("Running").endStatus()
			.build();
		server.expect().get().withPath("/api/v1/namespaces/test-ns/pods/test-pod").andReturn(200, podPending).once();
		server.expect().get().withPath("/api/v1/namespaces/test-ns/pods/test-pod").andReturn(200, podPending).once();
		server.expect().get().withPath("/api/v1/namespaces/test-ns/pods/test-pod").andReturn(200, podRunning).once();

		// When
		k8sApiClient.waitForResourcePhase("pod", "test-pod", "test-ns", "Running", 10, 1);

		// Then
		assertThat(server.getRequestCount()).isEqualTo(3);
	}

	@Test
	void waitForResourcePhaseThrowsExceptionOnTimeout() {
		// Given
		var podPending = new PodBuilder()
			.withNewMetadata()
			.withName("test-pod")
			.withNamespace("test-ns")
			.endMetadata()
			.withNewStatus()
			.withPhase("Pending")
			.endStatus()
			.build();

		server.expect()
			  .get()
			  .withPath("/api/v1/namespaces/test-ns/pods/test-pod")
			  .andReturn(200, podPending)
			  .always();

		// When/Then
		var exception = assertThrows(
			RuntimeException.class,
			() -> k8sApiClient.waitForResourcePhase("pod", "test-pod", "test-ns", "Running", 2, 1)
		);
		assertThat(exception.getMessage()).contains("Timeout reached");
	}

	@Test
	void waitForResourcePhaseWithDefaultTimeout() {
		// Given
		var podRunning = new PodBuilder()
			.withNewMetadata().withName("test-pod").withNamespace("test-ns").endMetadata()
			.withNewStatus().withPhase("Running").endStatus()
			.build();
		server.expect().get().withPath("/api/v1/namespaces/test-ns/pods/test-pod").andReturn(200, podRunning).always();

		// When
		k8sApiClient.waitForResourcePhase("pod", "test-pod", "test-ns", "Running");

		// Then
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void waitForResourcePhaseValidatesParameters() {
		// When/Then
		var exception = assertThrows(
			IllegalArgumentException.class,
			() -> k8sApiClient.waitForResourcePhase("", "test-pod", "test-ns", "Running", 60, 1)
		);
		assertThat(exception.getMessage()).contains("Resource type");

		exception = assertThrows(
			IllegalArgumentException.class,
			() -> k8sApiClient.waitForResourcePhase("pod", "test-pod", "test-ns", "Running", 0, 1)
		);
		assertThat(exception.getMessage()).contains("Timeout");

		exception = assertThrows(
			IllegalArgumentException.class,
			() -> k8sApiClient.waitForResourcePhase("pod", "test-pod", "test-ns", "Running", 60, 0)
		);
		assertThat(exception.getMessage()).contains("check interval");
	}

	// ========================================
	// Edge Cases and Error Handling Tests
	// ========================================

	@Test
	void resolvesDefaultNamespaceForEmptyString() throws InterruptedException {
		// Given
		server.expect()
			  .post()
			  .withPath("/api/v1/namespaces/default/secrets")
			  .andReturn(
				  201, new SecretBuilder()
					  .withNewMetadata().withName("test-secret").withNamespace("default").endMetadata()
					  .withType("Opaque")
					  .build()
			  )
			  .once();

		// When
		k8sApiClient.createSecret("Opaque", "test-secret", "", new Tuple("key", "value"));

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/namespaces/default/secrets");
		Map<String, Object> body = parseJson(request.getUtf8Body());
		assertThat(((Map<String, Object>) body.get("metadata")).get("namespace")).isEqualTo("default");
	}

	@Test
	void handlesMultipleResourceTypesInGetResourceClient() throws InterruptedException {
		// Given
		var deployment = new io.fabric8.kubernetes.api.model.apps.DeploymentBuilder()
			.withNewMetadata().withName("test-deploy").withNamespace("default").endMetadata()
			.build();
		server.expect()
			  .get()
			  .withPath("/apis/apps/v1/namespaces/default/deployments/test-deploy")
			  .andReturn(200, deployment)
			  .once();
		server.expect()
			  .delete()
			  .withPath("/apis/apps/v1/namespaces/default/deployments/test-deploy")
			  .andReturn(200, new StatusBuilder().build())
			  .once();

		// When
		k8sApiClient.delete("deployment", "default", "test-deploy");

		// Then
		var request = server.getLastRequest();
		assertThat(request.getMethod()).isEqualTo("DELETE");
		assertThat(request.getPath()).isEqualTo("/apis/apps/v1/namespaces/default/deployments/test-deploy");
	}

	@Test
	void customResourceClassIsImmutable() {
		// When
		var cr = new K8sClient.CustomResource("test-ns", "test-name");

		// Then
		assertThat(cr.namespace()).isEqualTo("test-ns");
		assertThat(cr.name()).isEqualTo("test-name");
	}

	@Test
	void waitForResourcePhaseResolvesArgoCDCustomResourceViaDiscovery() {
		// Given
		server.expect()
			  .get()
			  .withPath("/apis")
			  .andReturn(
				  200, Map.of(
					  "groups", List.of(Map.of(
						  "name", "argoproj.io",
						  "preferredVersion", Map.of("version", "v1beta1"),
						  "versions", List.of(Map.of("version", "v1beta1"))
					  ))
				  )
			  )
			  .once();

		server.expect()
			  .get()
			  .withPath("/apis/argoproj.io/v1beta1")
			  .andReturn(
				  200, Map.of(
					  "resources", List.of(Map.of(
						  "name", "argocds",
						  "singularName", "argocd",
						  "namespaced", true,
						  "kind", "ArgoCD",
						  "shortNames", List.of()
					  ))
				  )
			  )
			  .once();

		GenericKubernetesResource argocdResource = new GenericKubernetesResourceBuilder()
			.withApiVersion("argoproj.io/v1beta1")
			.withKind("ArgoCD")
			.withNewMetadata()
			.withName("argocd")
			.withNamespace("argocd")
			.endMetadata()
			.addToAdditionalProperties("status", Map.of("phase", "Available"))
			.build();

		AtomicBoolean argocdResourceWasRequested = new AtomicBoolean(false);

		server.expect()
			  .get()
			  .withPath("/apis/argoproj.io/v1beta1/namespaces/argocd/argocds/argocd")
			  .andReply(
				  200, request -> {
					  argocdResourceWasRequested.set(true);
					  return argocdResource;
				  }
			  )
			  .once();

		// When
		k8sApiClient.waitForResourcePhase("argocd", "argocd", "argocd", "Available", 5, 1);

		// Then
		assertThat(argocdResourceWasRequested.get()).isTrue();
		assertThat(argocdResource.getApiVersion()).isEqualTo("argoproj.io/v1beta1");
		assertThat(argocdResource.getKind()).isEqualTo("ArgoCD");
		assertThat(argocdResource.getMetadata().getName()).isEqualTo("argocd");
		assertThat(argocdResource.getMetadata().getNamespace()).isEqualTo("argocd");
	}

	@Test
	void throwsKubernetesApiResourceNotFoundExceptionWhenCustomResourceCannotBeResolved() {
		// Given
		server.expect()
			  .get()
			  .withPath("/apis")
			  .andReturn(
				  200, Map.of(
					  "groups", List.of(Map.of(
						  "name", "argoproj.io",
						  "preferredVersion", Map.of("version", "v1beta1"),
						  "versions", List.of(Map.of("version", "v1beta1"))
					  ))
				  )
			  )
			  .once();

		server.expect()
			  .get()
			  .withPath("/apis/argoproj.io/v1beta1")
			  .andReturn(
				  200, Map.of(
					  "resources", List.of(Map.of(
						  "name", "argocds",
						  "singularName", "argocd",
						  "namespaced", true,
						  "kind", "ArgoCD",
						  "shortNames", List.of()
					  ))
				  )
			  )
			  .once();

		// When/Then
		var exception = assertThrows(
			K8sClient.KubernetesApiResourceNotFoundException.class,
			() -> k8sApiClient.getAnnotation("does-not-exist", "some-resource", "some-annotation", "argocd")
		);

		assertThat(exception.getMessage())
			.isEqualTo("No API resource found for custom resource type 'does-not-exist'");
	}

	private static Map<String, Object> parseJson(String json) {
		try {
			return OBJECT_MAPPER.readValue(json, JSON_MAP_TYPE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<Map<String, Object>> parseJsonList(String json) {
		try {
			return OBJECT_MAPPER.readValue(json, JSON_LIST_TYPE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
