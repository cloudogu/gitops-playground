package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.TestK8sHelper

import java.util.concurrent.TimeUnit
import groovy.util.logging.Slf4j

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This test ensures all Pods and Namespaces are available, runnning at a startet GOP with - more or less - defaulöt values.
 *
 * * To run locally: add -Dmicronaut.environments=full to your execute configuration
 **/
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
class FullProfileTestIT extends ProfileTestSetup {

	/**
	 * Gets path to kubeconfig */
	static final String EXAMPLE_APPS_NAMESPACE = 'example-apps-staging'

	@BeforeAll
	static void labelMyTest() {
		log.info '########### K8S SMOKE TESTS PROFILE full ###########'
		waitUntilAllPodsRunning()
	}

	private static void waitUntilAllPodsRunning() {
		// if cert-manager is online, argocd is online, too!
		TestK8sHelper.waitForAllPodsRunningInNamespace(EXAMPLE_APPS_NAMESPACE, "", 40, TimeUnit.MINUTES)
	}

	@Test
	void ensureJenkinsPodIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace('jenkins', 'jenkins')
	}

	@Test
	void ensureArgoCDIsOnlineAndPodsAreRunning() {
		String expectedPod1 = "argocd-application-controller"
		String expectedPod2 = "argocd-applicationset-controller"
		//        String expectedPod3 = "argocd-notifications-controller" // not stable
		String expectedPod4 = "argocd-redis"
		String expectedPod5 = "argocd-repo-server"
		String expectedPod6 = "argocd-server"

		List<String> expectedPods = [expectedPod1, expectedPod2, /* expectedPod3,*/ expectedPod4, expectedPod5, expectedPod6,]

		TestK8sHelper.waitForPodPrefixesRunningInNamespace('argocd', expectedPods)
	}

	@Test
	void ensureScmmPodIsStarted() {

		TestK8sHelper.waitForAllPodsRunningInNamespace('scm-manager')
	}

	@Test
	void ensureNamespacesExists() {
		List<String> expectedNamespaces = ["argocd",
		                                   "cert-manager",
		                                   "jenkins",
		                                   "registry",
		                                   "scm-manager",
		                                   "default",
		                                   "example-apps-production",
		                                   "example-apps-staging",
		                                   "ingress",
		                                   "kube-node-lease",
		                                   "kube-public",
		                                   "kube-system",
		                                   "monitoring",
		                                   "secrets"] as List<String>

		TestK8sHelper.waitForNamespaces(expectedNamespaces)
	}

	/**
	 * tests searches for ingress services and ensure ingress is used as loadbalancer*/
	@Test
	void ensureIngressIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace('ingress', 'traefik')
	}

	@Test
	void ensureCertManagerIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace('cert-manager')
	}

	@Test
	void ensureVaultIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace('secrets', 'vault-0')
	}

	@Test
	void ensureRegistryIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace('registry', 'docker-registry')
	}

	@Test
	void ensureExternalSecretsPodsRunning() {
		TestK8sHelper.waitForPodsMatchingRunningInNamespace('secrets', [
			'external-secrets'                : { String podName ->
				podName.startsWith('external-secrets-') &&
					!podName.startsWith('external-secrets-webhook') &&
					!podName.startsWith('external-secrets-cert-controller')
			},
			'external-secrets-webhook'        : { String podName -> podName.startsWith('external-secrets-webhook') },
			'external-secrets-cert-controller': { String podName -> podName.startsWith('external-secrets-cert-controller') },
		])
	}

}