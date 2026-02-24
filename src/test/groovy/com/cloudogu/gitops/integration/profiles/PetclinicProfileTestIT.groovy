package com.cloudogu.gitops.integration.profiles

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.awaitility.Awaitility
import org.awaitility.core.ConditionTimeoutException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import com.cloudogu.gitops.integration.TestK8sHelper

import java.util.concurrent.TimeUnit
import groovy.util.logging.Slf4j

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail

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
			Awaitility.await()
					.atMost(40, TimeUnit.MINUTES)
					.pollInterval(5, TimeUnit.SECONDS)
					.untilAsserted {
						waitUntilPetclinicIsRunning()
					}
		} catch (ConditionTimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods()
			fail('Cluster not ready, sth false.', timeoutEx)
		}
	}

	// Start condition
	private static void waitUntilPetclinicIsRunning() {
		// Check Pod
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			def actualPods = client.pods().inNamespace(exampleStagingNs).list().getItems()
			assert !actualPods.isEmpty(): "No pods found in petclinc - namespace: ${exampleStagingNs}"
			def notRunningPods = actualPods.findAll { pod -> pod.getStatus().getPhase() != "Running"
			}
			assert !actualPods.isEmpty() && notRunningPods.isEmpty(): "These pods in ${exampleStagingNs} are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}
	}

	@Test
	void ensurePetclinicIsRunningOnStages() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {

			// Check Pod
			def actualPods = client.pods().inNamespace(exampleStagingNs).list().getItems()

			assert !actualPods.isEmpty(): "No pods found in petclinc - namespace: ${exampleStagingNs}"

			def notRunningPods = actualPods.findAll { pod -> pod.getStatus().getPhase() != "Running"
			}

			assert notRunningPods.isEmpty(): "These pods in ${exampleStagingNs} are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"

		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full|content-examples")
	// operator can not install nginx
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
	// operator can not install nginx
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