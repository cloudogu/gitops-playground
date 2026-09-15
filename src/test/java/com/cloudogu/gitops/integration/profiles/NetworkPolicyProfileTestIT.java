package com.cloudogu.gitops.integration.profiles;

import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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

	private static void waitForNetworkPolicy(String namespace, String name) {
		Awaitility.await()
			.atMost(5, TimeUnit.MINUTES)
			.pollInterval(5, TimeUnit.SECONDS)
			.untilAsserted(() -> assertNetworkPolicyExists(namespace, name));
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
}
