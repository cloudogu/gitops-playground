package com.cloudogu.gitops.integration.profiles;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the full-secrets profile resolves credentials from Kubernetes Secrets and passes them to consumers.
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-secrets")
public class FullSecretsProfileTestIT extends ProfileTestSetup {

	private static final String SOURCE_NAMESPACE = "gop-job";

	@BeforeAll
	static void labelMyTest() {
		log.info("########### K8S CREDENTIAL TESTS PROFILE full-secrets ###########");
	}

	@Test
	void usesApplicationCredentialsFromSecret() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Secret source = secret(client, SOURCE_NAMESPACE, "argocd-credentials");
			String expectedUsername = secretValue(source, "username");
			String expectedPassword = secretValue(source, "password");

			Secret argocdSecret = secret(client, "argocd", "argocd-secret");
			assertThat(BCrypt.checkpw(expectedPassword, secretValue(argocdSecret, "admin.password"))).isTrue();

			assertCredentials(
				secret(client, "monitoring", "grafana-admin-credentials"),
				"admin-user",
				"admin-password",
				expectedUsername,
				expectedPassword
			);
			assertCredentials(
				secret(client, "secrets", "vault-user-credentials"),
				"username",
				"password",
				expectedUsername,
				expectedPassword
			);
		}
	}

	@Test
	void usesJenkinsCredentialsFromSecret() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Secret source = secret(client, SOURCE_NAMESPACE, "jenkins-credentials");
			assertCredentials(
				secret(client, "jenkins", "jenkins-credentials"),
				"jenkins-admin-user",
				"jenkins-admin-password",
				secretValue(source, "username"),
				secretValue(source, "password")
			);
		}
	}

	@Test
	void usesScmManagerCredentialsFromSecret() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Secret source = secret(client, SOURCE_NAMESPACE, "scm-tenant-credentials");
			assertCredentials(
				secret(client, "scm-manager", "scm-manager-credentials"),
				"SCM_WEBAPP_INITIALUSER",
				"SCM_WEBAPP_INITIALPASSWORD",
				secretValue(source, "username"),
				secretValue(source, "password")
			);
		}
	}

	@Test
	void usesRegistryCredentialsFromSecret() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Secret source = secret(client, SOURCE_NAMESPACE, "registry-credentials");
			String dockerConfig = secretValue(secret(client, "jenkins", "proxy-registry"), ".dockerconfigjson");

			assertThat(dockerConfig)
				.contains(secretValue(source, "username"))
				.contains(secretValue(source, "password"));
		}
	}

	private static void assertCredentials(
		Secret secret,
		String usernameKey,
		String passwordKey,
		String expectedUsername,
		String expectedPassword
	) {
		assertThat(secretValue(secret, usernameKey)).isEqualTo(expectedUsername);
		assertThat(secretValue(secret, passwordKey)).isEqualTo(expectedPassword);
	}

	private static Secret secret(KubernetesClient client, String namespace, String name) {
		Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
		assertThat(secret)
			.as("Secret %s/%s", namespace, name)
			.isNotNull();
		return secret;
	}

	private static String secretValue(Secret secret, String key) {
		if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
			return secret.getStringData().get(key);
		}

		assertThat(secret.getData())
			.as("Secret %s/%s data", secret.getMetadata().getNamespace(), secret.getMetadata().getName())
			.containsKey(key);
		return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
	}
}
