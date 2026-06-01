package com.cloudogu.gitops.integration.profiles

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail

import com.cloudogu.gitops.integration.TestK8sHelper

import java.util.concurrent.TimeUnit
import groovy.util.logging.Slf4j

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.awaitility.core.ConditionTimeoutException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This tests can only be successfull, if one of theses profiles used.
 *
 * * To run locally: add -Dmicronaut.environments=content-examples to your execute configuration*/
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
class PetclinicProfileTestIT extends ProfileTestSetup {

	static String exampleStagingNs = 'example-apps-staging'

	@BeforeAll
	static void labelTest() {
		println "###### Testing Petclinic ######"
		// petclinic need most of time to run. If online, we can start all tests.
		try {
			TestK8sHelper.waitForAllPodsRunningInNamespace(exampleStagingNs, "", 40, TimeUnit.MINUTES)
		} catch (ConditionTimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods()
			fail('Cluster not ready, sth false.', timeoutEx)
		}
	}

	@Test
	void ensurePetclinicIsRunningOnStages() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(exampleStagingNs)
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
	@Test
	void ensurePetclinicIngressIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			def nameOfServiceAndIngress = "spring-petclinic-plain"
			// check Ingress
			def ingress = client.network()
				.v1()
				.ingresses()
				.inNamespace(exampleStagingNs)
				.withName(nameOfServiceAndIngress)
				.get()

			assert ingress != null: "Ingress '${nameOfServiceAndIngress}' not found in '${exampleStagingNs}'"

			def hosts = (ingress.spec?.rules ?: [])
				.collect { it?.host }
				.findAll { it }

			assert hosts.get(0).contains("petclinic") // in this case, petclinic do not care about prefix
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
	@Test
	void ensurePetclinicServidsdsdceIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {

			// Check Service
			def nameOfServiceAndIngress = "spring-petclinic-plain"
			def service = client.services()
				.inNamespace(exampleStagingNs)
				.withName(nameOfServiceAndIngress)
				.get()

			assertThat(service).isNotNull()

		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}
	}

}