package com.cloudogu.gitops.integration.profiles;

import com.cloudogu.gitops.integration.Polling;
import com.cloudogu.gitops.integration.TestK8sHelper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the complete external Vault flow: Vault -> External Secrets Operator -> Kubernetes Secret.
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-external-vault")
public class ExternalVaultProfileTestIT extends ProfileTestSetup {

	private static final String ESO_NAMESPACE = "secrets";
	private static final String TARGET_NAMESPACE = "external-vault-test";
	private static final String SECRET_STORE_NAME = "external-customer-vault";
	private static final String EXTERNAL_SECRET_NAME = "customer-credentials";
	private static final String EXPECTED_USERNAME = "customer-user";
	private static final String EXPECTED_PASSWORD = "customer-password";
	private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

	private static final ResourceDefinitionContext SECRET_STORE_CONTEXT = new ResourceDefinitionContext.Builder()
		.withGroup("external-secrets.io")
		.withVersion("v1beta1")
		.withKind("SecretStore")
		.withPlural("secretstores")
		.withNamespaced(true)
		.build();

	private static final ResourceDefinitionContext EXTERNAL_SECRET_CONTEXT = new ResourceDefinitionContext.Builder()
		.withGroup("external-secrets.io")
		.withVersion("v1beta1")
		.withKind("ExternalSecret")
		.withPlural("externalsecrets")
		.withNamespaced(true)
		.build();

	@BeforeAll
	static void waitForExternalSecretsOperator() {
		log.info("########### EXTERNAL VAULT PROFILE TESTS ###########");
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
			Polling.untilAsserted(() -> {
				GenericKubernetesResource secretStore = client.genericKubernetesResources(SECRET_STORE_CONTEXT)
					.inNamespace(TARGET_NAMESPACE)
					.withName(SECRET_STORE_NAME)
					.get();
				GenericKubernetesResource externalSecret = client.genericKubernetesResources(EXTERNAL_SECRET_CONTEXT)
					.inNamespace(TARGET_NAMESPACE)
					.withName(EXTERNAL_SECRET_NAME)
					.get();

				assertThat(secretStore).as("SecretStore %s/%s", TARGET_NAMESPACE, SECRET_STORE_NAME).isNotNull();
				assertThat(externalSecret).as("ExternalSecret %s/%s", TARGET_NAMESPACE, EXTERNAL_SECRET_NAME).isNotNull();

				assertReadyCondition(secretStore, "SecretStore", SECRET_STORE_NAME, "Valid");
				assertReadyCondition(externalSecret, "ExternalSecret", EXTERNAL_SECRET_NAME, "SecretSynced");
			}, SYNC_TIMEOUT, POLL_INTERVAL);
		}
	}

	@Test
	void synchronizesSecretFromExternalVault() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Polling.untilAsserted(() -> {
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
			}, SYNC_TIMEOUT, POLL_INTERVAL);
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
}
