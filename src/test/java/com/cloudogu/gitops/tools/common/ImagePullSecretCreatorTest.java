package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.credentials.CredentialsResolver;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class ImagePullSecretCreatorTest {

	private static final String NAMESPACE = "foo-my-ns";
	private static final String SECRET_NAME = "proxy-registry";

	KubernetesClient client;
	private K8sClient k8sClient;
	private ImagePullSecretCreator imagePullSecretCreator;

	@BeforeEach
	void init() {
		k8sClient = new K8sClient();
		k8sClient.setClient(client);
		imagePullSecretCreator = new ImagePullSecretCreator(k8sClient, new CredentialsResolver(k8sClient));
	}

	@Test
	void doesNotCreateImagePullSecretWhenDisabled() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(false);

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		assertThat(secret()).isNull();
	}

	@Test
	void createsImagePullSecretWithProxyCredentialsWhenProxyIsConfigured() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-pw");
		config.getRegistry().setUrl("url");
		config.getRegistry().setReadOnlyUsername("ROuser");
		config.getRegistry().setReadOnlyPassword("ROpw");
		config.getRegistry().setUsername("user");
		config.getRegistry().setPassword("pw");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		Secret secret = secret();

		assertThat(secret).isNotNull();
		assertThat(secret.getType()).isEqualTo("kubernetes.io/dockerconfigjson");
		assertDockerConfigContains(secret, "proxy-url", "proxy-user", "proxy-pw");
	}

	@Test
	void createsImagePullSecretWithReadOnlyCredentialsWhenProxyCredentialsAreNotConfigured() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setUrl("url");
		config.getRegistry().setReadOnlyUsername("ROuser");
		config.getRegistry().setReadOnlyPassword("ROpw");
		config.getRegistry().setUsername("user");
		config.getRegistry().setPassword("pw");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		Secret secret = secret();

		assertThat(secret).isNotNull();
		assertThat(secret.getType()).isEqualTo("kubernetes.io/dockerconfigjson");
		assertDockerConfigContains(secret, "url", "ROuser", "ROpw");
	}

	@Test
	void createsImagePullSecretWithDefaultCredentialsWhenReadOnlyCredentialsAreNotConfigured() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setUrl("url");
		config.getRegistry().setUsername("user");
		config.getRegistry().setPassword("pw");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		Secret secret = secret();

		assertThat(secret).isNotNull();
		assertThat(secret.getType()).isEqualTo("kubernetes.io/dockerconfigjson");
		assertDockerConfigContains(secret, "url", "user", "pw");
	}

	@Test
	void createsImagePullSecretWithProxySecretCredentials() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyCredentials(
			new Credentials(null, null, "proxy-credentials", "gop-job")
		);
		config.getRegistry().setUrl("url");
		config.getRegistry().setUsername("user");
		config.getRegistry().setPassword("pw");
		createCredentialsSecret("proxy-credentials", "proxy-secret-user", "proxy-secret-password");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		assertDockerConfigContains(secret(), "proxy-url", "proxy-secret-user", "proxy-secret-password");
	}

	@Test
	void createsImagePullSecretWithReadOnlySecretCredentials() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setUrl("url");
		config.getRegistry().setReadOnlyCredentials(
			new Credentials(null, null, "read-only-credentials", "gop-job")
		);
		config.getRegistry().setUsername("user");
		config.getRegistry().setPassword("pw");
		createCredentialsSecret("read-only-credentials", "read-only-secret-user", "read-only-secret-password");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		assertDockerConfigContains(secret(), "url", "read-only-secret-user", "read-only-secret-password");
	}

	@Test
	void createsImagePullSecretWithDefaultSecretCredentials() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setUrl("url");
		config.getRegistry().setCredentials(
			new Credentials(null, null, "registry-credentials", "gop-job")
		);
		createCredentialsSecret("registry-credentials", "secret-user", "secret-password");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		assertDockerConfigContains(secret(), "url", "secret-user", "secret-password");
	}

	@Test
	void createsNamespaceBeforeCreatingImagePullSecret() {
		Config config = new Config();
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getRegistry().setUrl("url");
		config.getRegistry().setUsername("user");
		config.getRegistry().setPassword("pw");

		imagePullSecretCreator.createIfRequired(
			ToolConfigMapperSupport.imagePullSecret(config.getRegistry()),
			NAMESPACE
		);

		assertThat(client.namespaces().withName(NAMESPACE).get()).isNotNull();
		assertThat(secret()).isNotNull();
	}

	private void createCredentialsSecret(String name, String username, String password) {
		Secret secret = new SecretBuilder()
			.withNewMetadata()
			.withName(name)
			.withNamespace("gop-job")
			.endMetadata()
			.withType("Opaque")
			.withData(Map.of(
				"username", Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)),
				"password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8))
			))
			.build();

		client.secrets().inNamespace("gop-job").resource(secret).create();
	}

	private Secret secret() {
		return client.secrets()
					 .inNamespace(NAMESPACE)
					 .withName(SECRET_NAME)
					 .get();
	}

	private static void assertDockerConfigContains(
		Secret secret,
		String expectedUrl,
		String expectedUsername,
		String expectedPassword) {
		String dockerConfigJson = decodeSecretValue(secret, ".dockerconfigjson");

		assertThat(dockerConfigJson).contains(expectedUrl);
		assertThat(dockerConfigJson).contains(expectedUsername);
		assertThat(dockerConfigJson).contains(expectedPassword);
	}

	private static String decodeSecretValue(Secret secret, String key) {
		if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
			return secret.getStringData().get(key);
		}

		if (secret.getData() != null && secret.getData().containsKey(key)) {
			return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
		}

		return null;
	}
}
