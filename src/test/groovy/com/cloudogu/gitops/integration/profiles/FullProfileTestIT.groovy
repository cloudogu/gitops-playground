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
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full") // operator can not load nginx
class FullProfileTestIT {

    /**
     * Gets path to kubeconfig */
    static final String RUNNING = "Running"
    static final String EXAMPLE_APPS_NAMESPACE = 'example-apps-staging'
    static final String NGINX_POD = 'nginx-helm-jenkins'

    @BeforeAll
    static void labelMyTest() {
        log.info  '########### K8S SMOKE TESTS PROFILE full ###########'
        waitUntilAllPodsRunning()
    }


    private static void waitUntilAllPodsRunning() {
        // if cert-manager is online, argocd is online, too!
        Awaitility.await().atMost(20, TimeUnit.MINUTES).untilAsserted {
            TestK8sHelper.checkAllPodsRunningInNamespace(EXAMPLE_APPS_NAMESPACE, NGINX_POD)
        }
    }

    @Test
    void ensureJenkinsPodIsStarted() {
        TestK8sHelper.checkAllPodsRunningInNamespace('jenkins', 'jenkins')
    }

    @Test
    void ensureArgoCDIsOnlineAndPodsAreRunning() {
        String expectedPod1 = "argocd-application-controller"
        String expectedPod2 = "argocd-applicationset-controller"
//        String expectedPod3 = "argocd-notifications-controller" // not stable
        String expectedPod4 = "argocd-redis"
        String expectedPod5 = "argocd-repo-server"
        String expectedPod6 = "argocd-server"

        List<String> expectedPods = [expectedPod1, expectedPod2, /* expectedPod3,*/ expectedPod4, expectedPod5, expectedPod6,]


        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            def actualPods = client.pods().inNamespace('argocd').list().getItems()

            // 1. Verify all expected pods are present
            def missingPods = expectedPods.findAll { prefix -> !actualPods.any { it.getMetadata().getName().startsWith(prefix) }
            }
            assert missingPods.isEmpty(): "Missing these pods in argocd: ${missingPods}"

            // 2. Verify all relevant pods are in 'Running' phase
            def notRunningPods = actualPods.findAll { pod -> expectedPods.any { prefix -> pod.getMetadata().getName().startsWith(prefix) }
            }.findAll { pod -> pod.getStatus().getPhase() != RUNNING
            }

            assert notRunningPods.isEmpty(): "These pods are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"

        } catch (KubernetesClientException ex) {
            fail("Unexpected Kubernetes exception", ex)
        }
    }

    @Test
    void ensureScmmPodIsStarted() {

        TestK8sHelper.checkAllPodsRunningInNamespace('scm-manager')
    }

    @Test
    void ensureNamespacesExists() {
        List<String> expectedNamespaces = ["argocd",
                                           "cert-manager",
                                           "jenkins",
                                           "registry",
                                           "scm-manager",
                                           "default",
                                           "example-apps-production",
                                           "example-apps-staging",
                                           "ingress-nginx",
                                           "kube-node-lease",
                                           "kube-public",
                                           "kube-system",
                                           "monitoring",
                                           "secrets"] as List<String>


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

/**
 * tests searches for ingress services and ensure ingress is used as loadbalancer*/
    @Test
    void ensureNginxIsOnline() {
        TestK8sHelper.checkAllPodsRunningInNamespace('ingress-nginx', 'ingress')
    }

    @Test
    void ensureCertManagerIsOnline() {
        TestK8sHelper.checkAllPodsRunningInNamespace('cert-manager')
    }

    @Test
    void ensureVaultIsOnline() {
        TestK8sHelper.checkAllPodsRunningInNamespace('secrets', 'vault-0')
    }

    @Test
    void ensureRegistryIsOnline() {
        TestK8sHelper.checkAllPodsRunningInNamespace('registry', 'docker-registry')
    }

    @Test
    void ensureExternalSecretsPodsRunning() {

        String expectedPod1 = "external-secrets-webhook"
        String expectedPod2 = "external-secrets-cert-controller"

        List<String> expectedPods = [expectedPod1, expectedPod2]

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            def actualPods = client.pods().inNamespace('secrets').list().getItems()

            // 1. Verify all expected pods are present
            def missingPods = expectedPods.findAll { prefix -> !actualPods.any { it.getMetadata().getName().startsWith(prefix) }
            }
            assert missingPods.isEmpty(): "Missing these pods in secrets: ${missingPods}"

            // 2. Verify all relevant pods are in 'Running' phase
            def notRunningPods = actualPods.findAll { pod -> expectedPods.any { prefix -> pod.getMetadata().getName().startsWith(prefix) }
            }.findAll { pod -> pod.getStatus().getPhase() != RUNNING
            }

            assert notRunningPods.isEmpty(): "These pods are not yet running: ${notRunningPods.collect { it.getMetadata().getName() + ':' + it.getStatus().getPhase() }}"

            // vault-0, external-secrets-webhook, external-secrets-<hash>, external-secrets-cert-controller
            assert actualPods.size() == 4

        } catch (KubernetesClientException ex) {
            fail("Unexpected Kubernetes exception", ex)
        }
    }




}