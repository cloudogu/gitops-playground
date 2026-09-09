package com.cloudogu.gitops.integration.profiles;

import com.cloudogu.gitops.integration.TestK8sHelper;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * This tests can only be successfull, if one of theses profiles used.
 *
 * <p>To run locally: add -Dmicronaut.environments=content-examples to your execute configuration
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
public class PetclinicProfileTestIT extends ProfileTestSetup {

	static String exampleStagingNs = "example-apps-staging";

	@BeforeAll
	static void labelTest() {
		System.out.println("###### Testing Petclinic ######");
		// petclinic need most of time to run. If online, we can start all tests.
		try {
			waitForContentExamplePrerequisites();
			TestK8sHelper.waitForAllPodsRunningInNamespace(exampleStagingNs, "", 40, TimeUnit.MINUTES);
		} catch (ConditionTimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods();
			fail("Cluster not ready, sth false.", timeoutEx);
		}
	}

	private static void waitForContentExamplePrerequisites() {
		TestK8sHelper.waitForNamespaces(List.of("jenkins", "registry", exampleStagingNs));
		TestK8sHelper.waitForAllPodsRunningInNamespace("registry", "docker-registry", 40);
		TestK8sHelper.waitForAllPodsRunningInNamespace("jenkins", "jenkins", 40);
	}

	@Test
	void ensurePetclinicIsRunningOnStages() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(exampleStagingNs);
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
	@Test
	void ensurePetclinicIngressIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			String nameOfServiceAndIngress = "spring-petclinic-plain";
			// check Ingress
			Ingress ingress = client.network()
					.v1()
					.ingresses()
					.inNamespace(exampleStagingNs)
					.withName(nameOfServiceAndIngress)
					.get();

			assertThat(ingress)
					.as("Ingress '%s' not found in '%s'", nameOfServiceAndIngress, exampleStagingNs)
					.isNotNull();

			List<IngressRule> rules = ingress.getSpec() == null || ingress.getSpec().getRules() == null
					? List.of()
					: ingress.getSpec().getRules();
			List<String> hosts = rules.stream()
					.map(rule -> rule == null ? null : rule.getHost())
					.filter(host -> host != null && !host.isEmpty())
					.toList();

			// in this case, petclinic do not care about prefix
			assertThat(hosts.get(0)).contains("petclinic");
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
	@Test
	void ensurePetclinicServidsdsdceIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Service
			String nameOfServiceAndIngress = "spring-petclinic-plain";
			Service service = client.services()
					.inNamespace(exampleStagingNs)
					.withName(nameOfServiceAndIngress)
					.get();

			assertThat(service).isNotNull();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}
}
