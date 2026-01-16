package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.features.KubenetesApiTestSetup
import groovy.util.logging.Slf4j
import io.kubernetes.client.openapi.models.V1Pod
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import static org.assertj.core.api.Assertions.assertThat

/**
 * This class checks if cert-manager is started well.
 * Cert-Manager contains own namespace ('cert-manager') which owns and 3 Pods:
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
class CertManagerProfileTestIT extends KubenetesApiTestSetup {

    String namespace = 'cert-manager'
    int sumOfPods = 3

    @Override
    boolean isReadyToStartTests() {
        // cert-manager should has 3 running pods
        def pods = api.listNamespacedPod(namespace).execute()
        if (pods.items.size() != 3) {
            return false
        }
        for (V1Pod pod : pods.getItems()) {
            println("Pod ${pod.getMetadata().name} with status ${pod.status.phase}")
            if (!"Running".equals(pod.status.phase)) {
                return false
            }
        }
        return true
    }

    @BeforeAll
    static void labelTest() {
        println "###### Profile CERT-MANAGER ######"
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
    void ensureNumberOfPodsAreEqualToSumOfPods() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods.getItems().size()).isEqualTo(sumOfPods)

    }

}
