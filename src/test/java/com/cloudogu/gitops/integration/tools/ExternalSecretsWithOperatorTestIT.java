package com.cloudogu.gitops.integration.tools;

import com.cloudogu.gitops.integration.Polling;
import com.cloudogu.gitops.integration.TestK8sHelper;
import com.cloudogu.gitops.integration.profiles.ProfileTestSetup;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * This class is for testing external secrets with and without ArgoCD Operator
 * <p>
 * This tests can only be successfull, if one of theses profiles used.
 *
 * <p>To run locally: add -Dmicronaut.environments=content-examples to your execute configuration
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|operator-full")
public class ExternalSecretsWithOperatorTestIT extends ProfileTestSetup {

	static String namespaceToTest = "secrets";

	@BeforeAll
	static void labelTest() {
		System.out.println("###### Testing External Secrets ######");
		try {
			waitForContentExamplePrerequisites();
			TestK8sHelper.waitForAllPodsRunningInNamespace(namespaceToTest, "", 40, TimeUnit.MINUTES);
		} catch (Polling.TimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods();
			fail("Cluster not ready, sth false.", timeoutEx);
		}
	}

	private static void waitForContentExamplePrerequisites() {
		TestK8sHelper.waitForNamespaces(List.of(namespaceToTest));
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespaceToTest, "vault", 40);
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespaceToTest, "external-secrets", 40);
	}

	@Test
	void ensureSecretsdeployt() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespaceToTest);
	}


	@Test
	void ensureVaultInternalServicesOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Service
			String nameOfServiceAndIngress = "vault-internal";
			Service service = client.services()
									.inNamespace(namespaceToTest)
									.withName(nameOfServiceAndIngress)
									.get();

			assertThat(service).isNotNull();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}

	@Test
	void ensureVaultServiceIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Service
			String nameOfServiceAndIngress = "vault";
			Service service = client.services()
									.inNamespace(namespaceToTest)
									.withName(nameOfServiceAndIngress)
									.get();

			assertThat(service).isNotNull();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}

	@Test
	void ensureVaultUiServiceIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Service
			String nameOfServiceAndIngress = "vault-ui";
			Service service = client.services()
									.inNamespace(namespaceToTest)
									.withName(nameOfServiceAndIngress)
									.get();

			assertThat(service).isNotNull();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}

	@Test
	void ensureExtenalsSecretsServiceServiceIsOnline() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			// Check Service
			String nameOfServiceAndIngress = "external-secrets-webhook";
			Service service = client.services()
									.inNamespace(namespaceToTest)
									.withName(nameOfServiceAndIngress)
									.get();

			assertThat(service).isNotNull();
		} catch (KubernetesClientException ex) {
			fail("Unexpected Kubernetes exception", ex);
		}
	}
}
