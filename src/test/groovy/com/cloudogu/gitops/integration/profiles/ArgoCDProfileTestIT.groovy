package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.features.KubenetesApiTestSetup
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import static org.assertj.core.api.Assertions.assertThat

/**
 * This tests can only be successfull, if one of theses profiles used.
 */

@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|minimal|operator-full|content-examples|operator-minimal|operator-content-examples")
class ArgoCDProfileTestIT extends KubenetesApiTestSetup {

    String namespace = 'argocd'

    @BeforeAll
    static void labelTest() {
        println "###### Integration ArgoCD test ######"
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

        V1PodList list = api.listNamespacedPod(namespace )
                .execute()
        List<V1Pod> argoPods = list.getItems().findAll { it.getMetadata().getName().startsWith("argo") }
        assertThat(argoPods.size()).isGreaterThanOrEqualTo(expectedSumOfArgoPods) // 6 or 7 depends on operator

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

            if (argoPods.size() >= 6)
            {
                return "Running".equals(argoPods.get(0).status.phase)
            }
        }
        return false
    }
}