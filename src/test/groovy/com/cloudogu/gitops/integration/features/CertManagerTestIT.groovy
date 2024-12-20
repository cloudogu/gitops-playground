package com.cloudogu.gitops.integration.features

import io.kubernetes.client.openapi.models.V1Pod
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

/**
 * This class checks if cert-manager is started well.
 * Cert-Manager contains own namespace ('cert-manager') which owns and 3 Pods:
 */
class CertManagerTestIT extends KubenetesApiTestSetup {

    String namespace = 'cert-manager'
    String certManagerPodName = 'cert-manager-'
    int sumOfPods = 3

    @Override
    boolean isReadyToStartTests() {

        def pods = api.listNamespacedPod(namespace).execute()
        if (pods && !pods.items.isEmpty()) {
            def certManagerPod = pods.items.find { it.getMetadata().name.startsWith(certManagerPodName) }
            if (certManagerPod) {
                return "Running".equals(certManagerPod.status.phase)
            }
        }
        return false;
    }

    @BeforeAll
    static void labelTest() {
        println "###### CERT-MANAGER ######"
    }

    @Test
    void ensureNamespaceExists() {
        def namespaces = api.listNamespace().execute()
        assertThat(namespaces).isNotNull()
        assertThat(namespaces.getItems().isEmpty()).isFalse()
        def namespace = namespaces.getItems().find { namespace.equals(it.getMetadata().name) }
        assertThat(namespace).isNotNull()

    }

    @Test
    void ensureAllCertManagerPodsAreExist() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods).isNotNull()
        assertThat(pods.getItems().isEmpty()).isFalse()

    }

    @Test
    void ensureAllCertManagerPodsAreStarted() {

        def pods = api.listNamespacedPod(namespace).execute()
        for (V1Pod pod : pods.getItems()) {
            assertThat(pod.status.phase).isEqualTo("Running")
        }
    }

    @Test
    void ensureNumberOfPodsAre3() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods.getItems().size()).isEqualTo(sumOfPods)

    }

}
