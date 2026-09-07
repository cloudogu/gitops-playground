package com.cloudogu.gitops.integration.profiles;

import com.cloudogu.gitops.integration.TestK8sHelper;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * This test ensures all Pods and Namespaces are available, runnning at a startet GOP with - more or less - defaulöt values.
 *
 * <p>To run locally: add -Dmicronaut.environments=full to your execute configuration
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "operator-mandants")
public class MandantProfileTestIT extends ProfileTestSetup {

	/** Gets path to kubeconfig. */
	static final String RUNNING = "Running";
	static final String TENANT_POD_FOR_CONDITION = "argocd-application-controller";
	static final String TENANT_NAMESPACE_ARGOCD = "tenant1-argocd";
	static final String TENANT_NAMESPACE_REGISTRY = "tenant1-registry";
	static final String TENANT_NAMESPACE_SCM = "tenant1-scm-manager";

	@BeforeAll
	static void labelMyTest() {
		log.info("###########  PROFILE Operator-Mandants ###########");
		waitUntilTenantIsReady();
	}

	private static void waitUntilTenantIsReady() {
		// tenant is created very late after running GOP twice!
		Awaitility.await()
				  .atMost(40, TimeUnit.MINUTES)
				  .pollInterval(5, TimeUnit.SECONDS)
				  .untilAsserted(() -> assertThat(
					  TestK8sHelper.checkAllPodsRunningInNamespace(
						  TENANT_NAMESPACE_REGISTRY,
						  "docker-registry"
					  ) && TestK8sHelper.checkAllPodsRunningInNamespace(
						  TENANT_NAMESPACE_SCM,
						  "scmm-"
					  )
				  ).isTrue());
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "operator-mandants")
	// just local
	@Test
	void ensureJenkinsPodIsStartedOnTenant() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("tenant1-jenkins", "jenkins");
	}

	@Test
	void ensureRegistryPodIsStartedOnTenant() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("tenant1-registry", "docker-registry");
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "operator-mandants")
	// just local
	@Test
	void ensureArgocdPodsAreStartedOnTenant() {
		String argocdNamespace = TENANT_NAMESPACE_ARGOCD;
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-application-controller");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-applicationset-controller");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-redis");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-repo-server");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-server");
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "operator-mandants")
	// just local
	@Test
	void ensureArgocdPodsAreStartedOnCentral() {
		String argocdNamespace = "argocd";
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-application-controller");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-applicationset-controller");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-redis");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-repo-server");
		TestK8sHelper.waitForAllPodsRunningInNamespace(argocdNamespace, "argocd-server");
	}

	@Test
	void ensureScmmPodIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace("scm-manager");
	}

	@DisabledIfSystemProperty(named = "micronaut.environments", matches = "operator-mandants")
	// just local
	@Test
	void ensureNamespacesExists() {
		List<String> expectedNamespaces = List.of(
				"argocd",
				"argocd-operator-system",
				"scm-manager",
				"default",
				"tenant1-argocd",
				"tenant1-jenkins",
				"tenant1-registry",
				"tenant1-example-apps-staging",
				"tenant1-example-apps-staging",
				"tenant1-scm-manager",
				"kube-node-lease",
				"kube-public",
				"kube-system"
		);

		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			List<Namespace> currentNamespaces = client.namespaces().list().getItems();

			// 1. Verify all expected pods are present
			List<String> missingNamespaces = expectedNamespaces.stream()
					.filter(prefix -> currentNamespaces.stream()
							.noneMatch(namespace -> namespace.getMetadata().getName().startsWith(prefix)))
					.toList();
			assertThat(missingNamespaces)
					.as("Missing these Namespace: %s", missingNamespaces)
					.isEmpty();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}
}
