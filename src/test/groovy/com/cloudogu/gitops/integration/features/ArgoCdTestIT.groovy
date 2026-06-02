package com.cloudogu.gitops.integration.features

import static org.assertj.core.api.Assertions.assertThat

import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This class is for testing deployments via ArgoCD*/
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|minimal|operator-full|content-examples|operator-minimal|operator-content-examples")
class ArgoCdTestIT extends KubenetesApiTestSetup {

	String namespace = 'argocd'

	@BeforeAll
	static void labelTest() {
		println "###### ARGO CD ######"
	}

	@Test
	void ensureNamespaceExists() {
		def namespaces = api.listNamespace().execute()
		assertThat(namespaces).isNotNull()
		assertThat(namespaces.getItems().isEmpty()).isFalse()
		def namespace = namespaces.getItems().find { namespace.equals(it.getMetadata().name) }
		assertThat(namespace).isNotNull()
	}

	/**
	 * ArgoCD uses 7 pods. All have to run*/
	@Test
	void ensureArgoCDIsOnlineAndRunning() {
		def expectedSumOfArgoPods = 6

		V1PodList list = api.listNamespacedPod(namespace)
			.execute()
		List<V1Pod> argoPods = list.getItems().findAll { it.getMetadata().getName().startsWith("argo") }
		assertThat(argoPods.size()).isEqualTo(expectedSumOfArgoPods)

		for (V1Pod pod : argoPods) {
			assertThat(pod.status.phase).isEqualTo("Running")
		}

	}

	@Override
	boolean isReadyToStartTests() {
		V1PodList list = api.listPodForAllNamespaces()
			.execute()
		if (list && !list.items.isEmpty()) {

			List<V1Pod> argoPods = list.getItems().findAll { it.getMetadata().getName().startsWith("argo") }
			if (argoPods.size() == 6) {
				return "Running".equals(argoPods.get(0).status.phase)
			}
		}
		return false
	}
}