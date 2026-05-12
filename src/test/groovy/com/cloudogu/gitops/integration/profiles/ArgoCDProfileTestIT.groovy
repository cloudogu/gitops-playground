package com.cloudogu.gitops.integration.profiles

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail

import java.util.concurrent.TimeUnit

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This tests can only be successfull, if one of theses profiles used.
 *
 * To run locally: add -Dmicronaut.environments=full to your execute configuration*/

@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|minimal|operator-full|content-examples|operator-minimal|operator-content-examples")
class ArgoCDProfileTestIT extends ProfileTestSetup {

	String namespace = 'argocd'

	@BeforeAll
	static void labelTest() {
		println "###### Integration ArgoCD test ######"
	}

	@Test
	void ensureNamespaceExists() {

		try (KubernetesClient client = new KubernetesClientBuilder().build()) {

			def argocdNamespace = client.namespaces().withName(namespace).get()

			assertThat(argocdNamespace).isNotNull()

		} catch (KubernetesClientException ex) {
			// Handle exception
			assert fail("not expected exception was thrown. ", ex)
		}

	}

	/**
	 * chechs that ArgoCD pods running **/
	@Test
	void ensureArgoCDIsOnlineAndPodsAreRunning() {
		String expectedPod1 = "argocd-application-controller"
		String expectedPod2 = "argocd-applicationset-controller"
		//        String expectedPod3 = "argocd-notifications-controller" // not stable
		String expectedPod4 = "argocd-redis"
		String expectedPod5 = "argocd-repo-server"
		String expectedPod6 = "argocd-server"

		List<String> expectedPods = [expectedPod1, expectedPod2, /* expectedPod3,*/ expectedPod4, expectedPod5, expectedPod6,]

		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Awaitility.await().atMost(40, TimeUnit.MINUTES).untilAsserted {
				def actualPods = client.pods().inNamespace(namespace).list().getItems()

				// 1. Verify all expected pods are present
				def missingPods = expectedPods.findAll { prefix -> !actualPods.any { it.getMetadata().getName().startsWith(prefix) }
				}
				assert missingPods.isEmpty(): "Missing these pods in argocd: ${missingPods}"

				// 2. Verify all relevant pods are in 'Running' phase
				def notRunningPods = actualPods.findAll { pod -> expectedPods.any { prefix -> pod.getMetadata().getName().startsWith(prefix) }
				}.findAll { pod -> pod.getStatus().getPhase() != "Running"
				}

				assert notRunningPods.isEmpty(): "These pods are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"
			}
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}
	}
}