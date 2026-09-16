package com.cloudogu.gitops.integration.profiles;

import com.cloudogu.gitops.integration.TestK8sHelper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * This test ensures all Pods and Namespaces are available, runnning at a startet GOP with - more or less - defaulöt values.
 *
 * <p>To run locally: add -Dmicronaut.environments=full to your execute configuration
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
public class FullProfileTestIT extends ProfileTestSetup {

	/** Gets path to kubeconfig. */
	static final String EXAMPLE_APPS_NAMESPACE = "example-apps-staging";

	@BeforeAll
	static void labelMyTest() {
		log.info("########### K8S SMOKE TESTS PROFILE full ###########");
	}

	@Test
	void ensureExampleAppsAreRunning() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(EXAMPLE_APPS_NAMESPACE, "", 40, TimeUnit.MINUTES);
	}

	@Test
	void ensureJenkinsPodIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("jenkins", "jenkins");
	}

	@Test
	void ensureArgoCDIsOnlineAndPodsAreRunning() {
		String expectedPod1 = "argocd-application-controller";
		String expectedPod2 = "argocd-applicationset-controller";
		// String expectedPod3 = "argocd-notifications-controller"; // not stable
		String expectedPod4 = "argocd-redis";
		String expectedPod5 = "argocd-repo-server";
		String expectedPod6 = "argocd-server";

		List<String> expectedPods = List.of(
				expectedPod1,
				expectedPod2,
				/* expectedPod3, */ expectedPod4,
				expectedPod5,
				expectedPod6
		);
		TestK8sHelper.waitForPodPrefixesRunningInNamespace("argocd", expectedPods);
	}

	@Test
	void ensureScmmPodIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("scm-manager");
	}

	@Test
	void ensureNamespacesExists() {
		List<String> expectedNamespaces = List.of(
				"argocd",
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
				"secrets"
		);
		TestK8sHelper.waitForNamespaces(expectedNamespaces);
	}

	/** tests searches for ingress services and ensure ingress is used as loadbalancer */
	@Test
	void ensureIngressIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("ingress", "traefik");
	}

	@Test
	void ensureCertManagerIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("cert-manager");
	}

	@Test
	void ensureVaultIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("secrets", "vault-0");
	}

	@Test
	void ensureRegistryIsOnline() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("registry", "docker-registry");
	}

	@Test
	void ensureExternalSecretsPodsRunning() {
		Map<String, Predicate<String>> expectedPods = new LinkedHashMap<>();
		expectedPods.put(
				"external-secrets",
				podName -> podName.startsWith("external-secrets-")
						&& !podName.startsWith("external-secrets-webhook")
						&& !podName.startsWith("external-secrets-cert-controller")
		);
		expectedPods.put(
				"external-secrets-webhook",
				podName -> podName.startsWith("external-secrets-webhook")
		);
		expectedPods.put(
				"external-secrets-cert-controller",
				podName -> podName.startsWith("external-secrets-cert-controller")
		);

		TestK8sHelper.waitForPodsMatchingRunningInNamespace("secrets", expectedPods);
	}
}
