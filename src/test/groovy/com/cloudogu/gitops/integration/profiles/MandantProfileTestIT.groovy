package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.TestK8sHelper
import groovy.util.logging.Slf4j
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import java.util.concurrent.TimeUnit

import static org.assertj.core.api.Assertions.fail

/**
 * This test ensures all Pods and Namespaces are available, runnning at a startet GOP with - more or less - defaulöt values.
 *
 * * To run locally: add -Dmicronaut.environments=full to your execute configuration
 **/
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "operator-mandants")
// operator can not load nginx
class MandantProfileTestIT extends ProfileTestSetup {

    /**
     * Gets path to kubeconfig */
    static final String RUNNING = "Running"
    static final String TENANT_POD_FOR_CONDITION = 'argocd-application-controller'
    static final String TENANT_NAMESPACE_ARGOCD = 'tenant1-argocd'

    @BeforeAll
    static void labelMyTest() {
        log.info '###########  PROFILE Operator-Mandants ###########'
        waitUntilTenantIsReady()
    }

    private static void waitUntilTenantIsReady() {
        // tenant is created very late after running GOP twice!
        Awaitility.await().atMost(30, TimeUnit.MINUTES).untilAsserted {
            assert TestK8sHelper.checkAllPodsRunningInNamespace(TENANT_NAMESPACE_ARGOCD, "argocd-application-controller") &&
                    TestK8sHelper.checkAllPodsRunningInNamespace(TENANT_NAMESPACE_ARGOCD, 'argocd-repo-server') &&
                    TestK8sHelper.checkAllPodsRunningInNamespace(TENANT_NAMESPACE_ARGOCD, 'argocd-notifications-controller')
        }
    }
    @Test
    void ensureJenkinsPodIsStartedOnTenant() {
        TestK8sHelper.checkAllPodsRunningInNamespace('tenant1-jenkins', 'jenkins')
    }

    @Test
    void ensureRegistryPodIsStartedOnTenant() {
        TestK8sHelper.checkAllPodsRunningInNamespace('tenant1-registry', 'docker-registry')
    }

    @Test
    void ensureArgocdPodsAreStartedOnTenant() {
        def argocdNamespace = TENANT_NAMESPACE_ARGOCD
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-application-controller')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-applicationset-controller')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-redis')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-repo-server')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-server')
    }

    @Test
    void ensureArgocdPodsAreStartedOnCentral() {
        def argocdNamespace = 'argocd'
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-application-controller')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-applicationset-controller')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-redis')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-repo-server')
        TestK8sHelper.checkAllPodsRunningInNamespace(argocdNamespace, 'argocd-server')
    }

    @Test
    void ensureScmmPodIsStarted() {

        TestK8sHelper.checkAllPodsRunningInNamespace('scm-manager')
    }

    @Test
    void ensureNamespacesExists() {
        List<String> expectedNamespaces = ["argocd",
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
                                           "kube-system"] as List<String>


        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            def currentNames = client.namespaces().list().getItems()

            // 1. Verify all expected pods are present
            def missingNamespace = expectedNamespaces.findAll { prefix -> !currentNames.any { it.getMetadata().getName().startsWith(prefix) }
            }
            assert missingNamespace.isEmpty(): "Missing these Namespace: ${missingNamespace}"


        } catch (KubernetesClientException ex) {
            fail("Unexpected Kubernetes exception", ex)
        }
    }
}