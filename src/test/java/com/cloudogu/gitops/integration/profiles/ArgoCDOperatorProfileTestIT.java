package com.cloudogu.gitops.integration.profiles;

import com.cloudogu.gitops.integration.Polling;
import com.cloudogu.gitops.integration.TestK8sHelper;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * This tests can only be successfull, if one of theses profiles used.
 *
 * <p>To run locally: add -Dmicronaut.environments=operator-full to your execute configuration
 */
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "operator-full|operator-minimal")
public class ArgoCDOperatorProfileTestIT extends ProfileTestSetup {

	static String namespaceOperator = "argocd-operator-system";
	static String namespaceArgocd = "argocd";

	@BeforeAll
	static void labelTest() {
		System.out.println("###### Integration ArgoCD Operator test ######");
		try {
			Polling.until(
				() -> TestK8sHelper.checkAllPodsRunningInNamespace(
							  namespaceOperator,
							  "argocd-operator-controller"
						  ) && TestK8sHelper.checkAllPodsRunningInNamespace(
							  namespaceArgocd,
							  "argocd-server"
						  ),
				Duration.ofMinutes(40),
				Duration.ofSeconds(5)
			);
		} catch (Polling.TimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods();
			fail("Cluster not ready, sth false.");
		}
	}

	@Test
	void ensureNamespaceExists() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Namespace argocdNamespace = client.namespaces().withName(namespaceOperator).get();

			assertThat(argocdNamespace).isNotNull();
			assertThat(argocdNamespace.getMetadata().getName()).isEqualTo(namespaceOperator);
		} catch (KubernetesClientException ex) {
			// Handle exception
			fail("not expected exception was thrown. ", ex);
		}
	}

	@Test
	void ensureOperatorNamespaceExists() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			Namespace argocdNamespace = client.namespaces().withName(namespaceArgocd).get();

			assertThat(argocdNamespace).isNotNull();
		} catch (KubernetesClientException ex) {
			// Handle exception
			fail("not expected exception was thrown. ", ex);
		}
	}
}
