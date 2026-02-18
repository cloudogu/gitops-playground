package com.cloudogu.gitops.integration.features


import static org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList

/**
 * This class is for testing deployments via ArgoCD*/
@Disabled("TODO: analyse why it fails exactly.")
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
        def expectedSumOfArgoPods = 7

        V1PodList list = api.listNamespacedPod(namespace )
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
            if (argoPods.size() == 7)
            {
                return "Running".equals(argoPods.get(0).status.phase)
            }
        }
        return false
    }
}