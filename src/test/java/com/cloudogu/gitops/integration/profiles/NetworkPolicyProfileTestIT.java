package com.cloudogu.gitops.integration.profiles;

import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies that the network policies required by the full network policy profiles are deployed.
 * Detailed selectors and ports are covered by the corresponding unit tests.
 */
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-netpols|operator-full-netpols")
public class NetworkPolicyProfileTestIT extends ProfileTestSetup {

	@BeforeAll
	static void labelTest() {
		System.out.println("###### Integration NetworkPolicy test ######");
	}

	@Test
	void ensureScmManagerAccessPolicyExists() {
		waitForNetworkPolicy("scm-manager", "allow-required-access-to-scm-manager");
	}

	@Test
	void ensureJenkinsAccessPolicyExists() {
		waitForNetworkPolicy("jenkins", "allow-required-access-to-jenkins");
	}

	@Test
	void ensureRegistryAccessPolicyExists() {
		waitForNetworkPolicy("registry", "allow-required-access-to-registry");
	}

	@Test
	@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-netpols")
	void ensureCertManagerNetworkPoliciesExist() {
		waitForNetworkPolicy("cert-manager", "restrict-cert-manager-ingress");
		waitForNetworkPolicy("cert-manager", "allow-required-access-to-cert-manager-webhook");
	}

	@Test
	void ensureExternalSecretsNetworkPoliciesExist() {
		waitForNetworkPolicy("secrets", "restrict-external-secrets-ingress");
		waitForNetworkPolicy("secrets", "allow-required-access-to-external-secrets-webhook");
	}

	@Test
	@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-netpols")
	void ensureVaultNetworkPolicyExists() {
		waitForNetworkPolicyWithSelector("secrets", Map.of("app.kubernetes.io/name", "vault"));
	}

	private static void waitForNetworkPolicy(String namespace, String name) {
		Awaitility.await()
			.atMost(5, TimeUnit.MINUTES)
			.pollInterval(5, TimeUnit.SECONDS)
			.untilAsserted(() -> assertNetworkPolicyExists(namespace, name));
	}

	private static void waitForNetworkPolicyWithSelector(String namespace, Map<String, String> selector) {
		Awaitility.await()
			.atMost(5, TimeUnit.MINUTES)
			.pollInterval(5, TimeUnit.SECONDS)
			.untilAsserted(() -> assertNetworkPolicyWithSelectorExists(namespace, selector));
	}

	private static void assertNetworkPolicyExists(String namespace, String name) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			NetworkPolicy networkPolicy = client.network()
				.v1()
				.networkPolicies()
				.inNamespace(namespace)
				.withName(name)
				.get();

			assertThat(networkPolicy)
				.as("NetworkPolicy '%s' not found in namespace '%s'", name, namespace)
				.isNotNull();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}

	private static void assertNetworkPolicyWithSelectorExists(String namespace, Map<String, String> selector) {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			assertThat(client.network()
				.v1()
				.networkPolicies()
				.inNamespace(namespace)
				.list()
				.getItems())
				.as("No NetworkPolicy with selector %s found in namespace '%s'", selector, namespace)
				.anySatisfy(networkPolicy -> assertThat(networkPolicy.getSpec()
					.getPodSelector()
					.getMatchLabels()).containsAllEntriesOf(selector));
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}
}
