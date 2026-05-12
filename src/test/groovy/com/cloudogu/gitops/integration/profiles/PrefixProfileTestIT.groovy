package com.cloudogu.gitops.integration.profiles

import static org.assertj.core.api.Assertions.fail

import com.cloudogu.gitops.integration.TestK8sHelper

import java.util.concurrent.TimeUnit
import groovy.util.logging.Slf4j

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.awaitility.Awaitility
import org.awaitility.core.ConditionTimeoutException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This tests can only be successfull, if one of theses profiles used.
 * * To run locally: add -Dmicronaut.environments=full-prefix to your execute configuration*/
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-prefix")
class PrefixProfileTestIT extends ProfileTestSetup {
	// is used for pre-condition
	static String exampleStagingNs = 'my-prefix-example-apps-staging'
	static String argocdNs = 'my-prefix-argocd'
	String scmManagerNs = 'my-prefix-scm-manager'
	String registryNs = 'my-prefix-registry'
	String ingressNs = 'my-prefix-ingress'
	/* Jenking can not start ingress*/
	static String certManagerNs = 'my-prefix-cert-manager'
	String jenkinsNs = 'my-prefix-jenkins'
	static String monitoringNs = 'my-prefix-monitoring'
	String secretsNs = 'my-prefix-secrets'
	String exampleProductionNs = 'my-prefix-example-apps-production'

	@BeforeAll
	static void labelTest() {
		log.info "###### Integration test for Prefix ######"

		try {
			Awaitility.await()
				.atMost(40, TimeUnit.MINUTES)
				.pollInterval(5, TimeUnit.SECONDS)
				.untilAsserted {
					waitUntilPetclinicIsRunning()
				}
		} catch (ConditionTimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods()
			fail('Cluster not ready, sth false.')
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
	void ensureNamespacesExistWithPrefix() {
		List<String> expectedNamespaces = [argocdNs,
		                                   scmManagerNs,
		                                   registryNs,
		                                   ingressNs,
		                                   certManagerNs,
		                                   jenkinsNs,
		                                   monitoringNs,
		                                   secretsNs,
		                                   exampleProductionNs,
		                                   exampleStagingNs]

		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			def currentNames = client.namespaces().list().getItems()

			// 1. Verify all expected pods are present
			def missingNamespace = expectedNamespaces.findAll { prefix -> !currentNames.any { it.getMetadata().getName().startsWith(prefix) }
			}
			assert missingNamespace.isEmpty(): "Missing these Namespace: ${missingNamespace}"
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}

	}

	@Test
	void ensurePodsAreRunningInPrefixedNamespaces() {
		List<String> namespacesToCheck = [argocdNs,
		                                  scmManagerNs,
		                                  registryNs,
		                                  certManagerNs,
		                                  monitoringNs]
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			namespacesToCheck.each { ns ->
				def actualPods = client.pods().inNamespace(ns).list().getItems()
				assert !actualPods.isEmpty(): "No pods found in namespace: ${ns}"
				def notRunningPods = actualPods.findAll { pod -> pod.getStatus().getPhase() != "Running"
				}
				assert notRunningPods.isEmpty(): "These pods in ${ns} are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"
			}
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex)
		}
	}
}