package com.cloudogu.gitops.integration.features

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat

/**
 * This class is for testing deployments via ArgoCD
 */
class ArgoCdTestIT extends FeatureTestSetup {

    String namespace = 'monitoring'

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
     * ArgoCD uses 7 pods. All have to run
     */
    @Test
    void ensureArgoCDIsOnlineAndRunning() {
        def expectedSumOfArgoPods = 7
        V1PodList list = api.listPodForAllNamespaces()
                .execute()
        List<V1Pod> argoPods = list.getItems().findAll { it.getMetadata().getName().startsWith("argo") }
        assertThat(argoPods.size()).isEqualTo(expectedSumOfArgoPods)

        for (V1Pod pod : argoPods) {
            assertThat(pod.status.phase).isEqualTo("Running")
        }

    }
}
