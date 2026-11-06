package com.cloudogu.gitops.integration.profiles;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.integration.Polling;
import com.cloudogu.gitops.integration.TestK8sHelper;
import com.cloudogu.gitops.utils.YamlUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the complete external Vault flow: Vault -> External Secrets Operator -> Kubernetes Secret.
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "(operator-)?full-external-vault(-(approle|kubernetes))?")
public class ExternalVaultProfileTestIT extends ProfileTestSetup {

	private static final String ESO_NAMESPACE = "secrets";
	private static final String TARGET_NAMESPACE = "external-vault-test";
	private static final String SECRET_STORE_NAME = "external-customer-vault";
	private static final String EXTERNAL_SECRET_NAME = "customer-credentials";
	private static final Properties TEST_FIXTURE = loadTestFixture();
	private static final String EXPECTED_USERNAME = fixtureValue("EXTERNAL_VAULT_TEST_USERNAME");
	private static final String EXPECTED_PASSWORD = fixtureValue("EXTERNAL_VAULT_TEST_PASSWORD");
	private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

	private static final ResourceDefinitionContext SECRET_STORE_CONTEXT = new ResourceDefinitionContext.Builder()
		.withGroup("external-secrets.io")
		.withVersion("v1")
		.withKind("SecretStore")
		.withPlural("secretstores")
		.withNamespaced(true)
		.build();

	private static final ResourceDefinitionContext EXTERNAL_SECRET_CONTEXT = new ResourceDefinitionContext.Builder()
		.withGroup("external-secrets.io")
		.withVersion("v1")
		.withKind("ExternalSecret")
		.withPlural("externalsecrets")
		.withNamespaced(true)
		.build();

	@BeforeAll
	static void verifyProfileAndWaitForExternalSecretsOperator() {
		log.info("########### EXTERNAL VAULT PROFILE TESTS ###########");
		String profileName = System.getProperty("micronaut.environments");
		Config profile = loadExternalVaultProfile(profileName);
		var secretsConfig = profile.getFeatures().getSecrets();
		assertThat(secretsConfig.getExternalSecrets().getActive())
			.as("%s must explicitly enable External Secrets Operator", profileName)
			.isTrue();
		assertThat(secretsConfig.getVault().getMode())
			.as("%s must not enable the GOP-managed Vault", profileName)
			.isNull();
		assertThat(secretsConfig.getExternalSecrets().getSecrets())
			.as("%s must configure the external Vault test secret", profileName)
			.anySatisfy(secret -> {
				assertThat(secret.getName()).isEqualTo(EXTERNAL_SECRET_NAME);
				assertThat(secret.getNamespace()).isEqualTo(TARGET_NAMESPACE);
			});
		if (profileName.startsWith("operator-")) {
			assertThat(profile.getFeatures().getArgocd().getOperator())
				.as("%s must use namespace-scoped Argo CD", profileName)
				.isTrue();
			assertThat(profile.getContent().getNamespaces())
				.as("%s must let Argo CD manage the external Vault target namespace", profileName)
				.contains(TARGET_NAMESPACE);
		}
		TestK8sHelper.waitForNamespaces(List.of(ESO_NAMESPACE, TARGET_NAMESPACE));
		TestK8sHelper.waitForAllPodsRunningInNamespace(ESO_NAMESPACE, "external-secrets", 10);
	}

	@Test
	void deploysExternalSecretsOperatorWithoutInternalVault() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			assertThat(client.apps().deployments().inNamespace(ESO_NAMESPACE).withName("external-secrets").get())
				.as("External Secrets Operator deployment")
				.isNotNull();
			assertThat(client.apps().statefulSets().inNamespace(ESO_NAMESPACE).withName("vault").get())
				.as("GOP-managed Vault must not be installed in external-only mode")
				.isNull();
			assertThat(client.services().inNamespace(ESO_NAMESPACE).withName("vault").get())
				.as("GOP-managed Vault service must not be installed in external-only mode")
				.isNull();
		}
	}

	@Test
	void createsReadyExternalSecretResources() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Polling.untilAsserted(
				() -> {
					GenericKubernetesResource secretStore = client.genericKubernetesResources(SECRET_STORE_CONTEXT)
																  .inNamespace(TARGET_NAMESPACE)
																  .withName(SECRET_STORE_NAME)
																  .get();
					GenericKubernetesResource externalSecret = client.genericKubernetesResources(EXTERNAL_SECRET_CONTEXT)
																	 .inNamespace(TARGET_NAMESPACE)
																	 .withName(EXTERNAL_SECRET_NAME)
																	 .get();

					assertThat(secretStore).as("SecretStore %s/%s", TARGET_NAMESPACE, SECRET_STORE_NAME).isNotNull();
					assertThat(externalSecret).as(
						"ExternalSecret %s/%s",
						TARGET_NAMESPACE,
						EXTERNAL_SECRET_NAME
					).isNotNull();

					assertReadyCondition(secretStore, "SecretStore", SECRET_STORE_NAME, "Valid");
					assertReadyCondition(externalSecret, "ExternalSecret", EXTERNAL_SECRET_NAME, "SecretSynced");
				}, SYNC_TIMEOUT, POLL_INTERVAL
			);
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-external-vault-approle")
	void usesAppRoleSecretReference() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Polling.untilAsserted(
				() -> {
					GenericKubernetesResource store = client.genericKubernetesResources(SECRET_STORE_CONTEXT)
															.inNamespace(TARGET_NAMESPACE)
															.withName(SECRET_STORE_NAME)
															.get();
					assertThat(store).isNotNull();

					JsonNode auth = new ObjectMapper().valueToTree(store.getAdditionalProperties())
													  .path("spec").path("provider").path("vault").path("auth");
					assertThat(auth.size()).as("Exactly one Vault authentication method").isEqualTo(1);
					JsonNode appRole = auth.path("appRole");
					assertThat(appRole.isObject()).isTrue();
					assertThat(appRole.path("path").asText()).isEqualTo("approle");
					assertThat(appRole.path("roleId").asText()).isEqualTo("gop-eso-local-test");
					assertThat(appRole.path("secretRef").path("name").asText()).isEqualTo("vault-approle");
					assertThat(appRole.path("secretRef").path("key").asText()).isEqualTo("secret-id");
					assertThat(appRole.has("secretId")).isFalse();
				}, SYNC_TIMEOUT, POLL_INTERVAL
			);

			Secret credentials = client.secrets().inNamespace(TARGET_NAMESPACE).withName("vault-approle").get();
			assertThat(credentials).isNotNull();
			assertThat(credentials.getData()).containsKey("secret-id");
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-external-vault-kubernetes")
	void usesKubernetesServiceAccountAuthentication() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Polling.untilAsserted(() -> {
				GenericKubernetesResource store = client.genericKubernetesResources(SECRET_STORE_CONTEXT)
					.inNamespace(TARGET_NAMESPACE)
					.withName(SECRET_STORE_NAME)
					.get();
				assertThat(store).isNotNull();

				JsonNode auth = new ObjectMapper().valueToTree(store.getAdditionalProperties())
					.path("spec").path("provider").path("vault").path("auth");
				assertThat(auth.size()).as("Exactly one Vault authentication method").isEqualTo(1);
				JsonNode kubernetes = auth.path("kubernetes");
				assertThat(kubernetes.isObject()).isTrue();
				assertThat(kubernetes.path("mountPath").asText()).isEqualTo("kubernetes");
				assertThat(kubernetes.path("role").asText()).isEqualTo("gop-eso");
				JsonNode account = kubernetes.path("serviceAccountRef");
				assertThat(account.path("name").asText()).isEqualTo("eso-vault-auth");
				assertThat(account.path("audiences").isArray()).isTrue();
				assertThat(account.path("audiences").size()).isEqualTo(1);
				assertThat(account.path("audiences").get(0).asText()).isEqualTo("vault");
			}, SYNC_TIMEOUT, POLL_INTERVAL);

			assertThat(client.serviceAccounts().inNamespace(TARGET_NAMESPACE).withName("eso-vault-auth").get())
				.as("Pre-existing Vault authentication service account")
				.isNotNull();
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-external-vault-kubernetes")
	void scopesEsoTokenRequestsToVaultServiceAccount() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Role role = client.rbac().roles().inNamespace(TARGET_NAMESPACE)
				.withName("eso-vault-token-request").get();
			assertThat(role).isNotNull();
			assertThat(role.getRules()).singleElement().satisfies(rule -> {
				assertThat(rule.getApiGroups()).containsExactly("");
				assertThat(rule.getResources()).containsExactly("serviceaccounts/token");
				assertThat(rule.getResourceNames()).containsExactly("eso-vault-auth");
				assertThat(rule.getVerbs()).containsExactly("create");
			});

			RoleBinding binding = client.rbac().roleBindings().inNamespace(TARGET_NAMESPACE)
				.withName("eso-vault-token-request").get();
			assertThat(binding).isNotNull();
			assertThat(binding.getRoleRef().getName()).isEqualTo("eso-vault-token-request");
			assertThat(binding.getSubjects()).singleElement().satisfies(subject -> {
				assertThat(subject.getKind()).isEqualTo("ServiceAccount");
				assertThat(subject.getName()).isEqualTo("external-secrets");
				assertThat(subject.getNamespace()).isEqualTo(ESO_NAMESPACE);
			});
		}
	}

	@Test
	void synchronizesSecretFromExternalVault() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Polling.untilAsserted(
				() -> {
					Secret secret = client.secrets().inNamespace(TARGET_NAMESPACE).withName(EXTERNAL_SECRET_NAME).get();
					assertThat(secret)
						.as("Secret %s/%s synchronized by ESO", TARGET_NAMESPACE, EXTERNAL_SECRET_NAME)
						.isNotNull();
					assertThat(secretValue(secret, "username")).isEqualTo(EXPECTED_USERNAME);
					assertThat(secretValue(secret, "password")).isEqualTo(EXPECTED_PASSWORD);
					assertThat(secret.getMetadata().getOwnerReferences()).anySatisfy(ownerReference -> {
						assertThat(ownerReference.getKind()).isEqualTo("ExternalSecret");
						assertThat(ownerReference.getName()).isEqualTo(EXTERNAL_SECRET_NAME);
					});
				}, SYNC_TIMEOUT, POLL_INTERVAL
			);
		}
	}

	private static void assertReadyCondition(
		GenericKubernetesResource resource,
		String kind,
		String name,
		String expectedReason
	) {
		String resourceDescription = String.format("%s %s/%s", kind, TARGET_NAMESPACE, name);

		Object statusValue = resource.getAdditionalProperties().get("status");
		assertThat(statusValue).as("%s status", resourceDescription).isInstanceOf(Map.class);

		@SuppressWarnings("unchecked")
		Map<String, Object> status = (Map<String, Object>) statusValue;
		Object conditionsValue = status.get("conditions");
		assertThat(conditionsValue).as("%s status.conditions", resourceDescription).isInstanceOf(List.class);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> conditions = (List<Map<String, Object>>) conditionsValue;
		assertThat(conditions)
			.as("%s Ready condition", resourceDescription)
			.anySatisfy(condition -> assertThat(condition)
				.containsEntry("type", "Ready")
				.containsEntry("status", "True")
				.containsEntry("reason", expectedReason));
	}

	private static String secretValue(Secret secret, String key) {
		assertThat(secret.getData()).containsKey(key);
		return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
	}

	private static Config loadExternalVaultProfile(String profileName) {
		String resourceName = "/application-" + profileName + ".yaml";
		try (InputStream input = ExternalVaultProfileTestIT.class.getResourceAsStream(resourceName)) {
			if (input == null) {
				throw new IllegalStateException("External Vault test profile not found: " + resourceName);
			}
			String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			return Config.fromMap(YamlUtils.parseYamlMap(yaml));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load external Vault test profile: " + resourceName, e);
		}
	}

	private static Properties loadTestFixture() {
		try (InputStream input = ExternalVaultProfileTestIT.class.getResourceAsStream(
			"/external-vault-test-fixture.properties"
		)) {
			if (input == null) {
				throw new IllegalStateException("External Vault test fixture not found on classpath");
			}
			Properties properties = new Properties();
			properties.load(input);
			return properties;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load external Vault test fixture", e);
		}
	}

	private static String fixtureValue(String key) {
		String value = TEST_FIXTURE.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Missing external Vault test fixture value: " + key);
		}
		return value;
	}
}
