package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.TestK8sHelper
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.awaitility.Awaitility
import org.awaitility.core.ConditionTimeoutException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import java.util.concurrent.TimeUnit

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail

/**
 * This tests can only be successfull, if one of theses profiles used.
 *
 * To run locally: add -Dmicronaut.environments=operator-full to your execute configuration
 */

@EnabledIfSystemProperty(named = "micronaut.environments", matches = "operator-full|operator-minimal")
class ArgoCDOperatorProfileTestIT {

    static String namespaceOperator = 'argocd-operator-system'
    static String namespaceArgocd = 'argocd'

    @BeforeAll
    static void labelTest() {
        println "###### Integration ArgoCD Operator test ######"
        try {
            Awaitility.await()
                    .atMost(20, TimeUnit.MINUTES)
                    .pollInterval(5, TimeUnit.SECONDS)
                    .untilAsserted {
                        assert TestK8sHelper.checkAllPodsRunningInNamespace(namespaceOperator, 'argocd-operator-controller') &&
                                TestK8sHelper.checkAllPodsRunningInNamespace(namespaceArgocd, 'argocd-server')
                    }
        } catch (ConditionTimeoutException timeoutEx) {
            TestK8sHelper.dumpNamespacesAndPods()
            fail('Cluster not ready, sth false.')
        }
    }

    @Test
    void ensureNamespaceExists() {

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            def argocdNamespace = client.namespaces().withName(namespaceOperator).get()

            assertThat(argocdNamespace).isNotNull()
            assert namespaceOperator.startsWith(argocdNamespace.metadata.name)

        } catch (KubernetesClientException ex) {
            // Handle exception
            assert fail("not expected exception was thrown. ", ex)
        }

    }

    @Test
    void ensureOperatorNamespaceExists() {

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            def argocdNamespace = client.namespaces().withName(namespaceArgocd).get()

            assertThat(argocdNamespace).isNotNull()

        } catch (KubernetesClientException ex) {
            // Handle exception
            assert fail("not expected exception was thrown. ", ex)
        }

    }

}