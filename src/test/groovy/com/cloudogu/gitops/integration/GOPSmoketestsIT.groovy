package com.cloudogu.gitops.integration

import com.cloudogu.gitops.integration.features.KubenetesApiTestSetup
import io.kubernetes.client.openapi.models.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

/**
 * This test ensures all Pods and Namespaces are available, runnning at a startet GOP with - more or less - defaulöt values.
 */
class GOPSmoketestsIT extends KubenetesApiTestSetup {

    /**
     * Gets path to kubeconfig
     */
    @BeforeAll
    static void labelMyTest() {
        println '########### K8S SMOKE TESTS ###########'
    }


    @Test
    void ensureJenkinsPodIsStarted() {

        V1PodList list = api.listPodForAllNamespaces()
                .execute();
        // invokes the CoreV1Api client
        V1Pod jenkinsPod = list.getItems().findAll { it.getMetadata().getName().startsWith("jenkins") }.get(0)
        assertThat(jenkinsPod.getMetadata().getName()).isEqualTo("jenkins-0")
    }

    @Test
    void ensureArgoCdPodsAreStartedExpect5() {

        def expectedArgoCDPods = 7
        V1PodList list = api.listPodForAllNamespaces()
                .execute()
        List<V1Pod> argocdPodList = list.getItems().findAll { it.getMetadata().getName().startsWith("argo") }
        assertThat(argocdPodList.size()).isEqualTo(expectedArgoCDPods)
    }

    @Test
    void ensureScmmPodIsStarted() {

        V1PodList list = api.listPodForAllNamespaces()
                .execute();
        for (V1Pod item : list.getItems()) {
            println item.getMetadata().getName()
        }
        // invokes the CoreV1Api client
        V1Pod scmmPod = list.getItems().findAll { it.getMetadata().getName().startsWith("scmm") }.get(0)
        assertThat(scmmPod.getMetadata().getName()).startsWith("scmm")
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


        V1NamespaceList list = api.listNamespace().execute()
        //      list.items.each {println it.getMetadata().getName()} // print namespaces
        List<String> listOfNamespaces = list.getItems().collect { it.getMetadata().name }
        assertThat(expectedNamespaces).containsAll(listOfNamespaces)

    }

    /**
     * tests searches for ingress services and ensure ingress is used as loadbalancer
     */
    @Test
    // kein nginx Service am laufen am Jenkins!
    void ensureNginxIsOnline() {
        def expectedIngressServices = 2;
        def services = api.listServiceForAllNamespaces().execute()

        for (def item : services.getItems()) {
            System.out.println("Service:" + item.getMetadata().getName())
        }
        def listOfIngessServices = services.getItems().findAll { it.getMetadata().getName().startsWith("ingress") }
        assertThat(listOfIngessServices.size()).isEqualTo(expectedIngressServices)
        def ingress = listOfIngessServices.find { it.getMetadata().getName().equals("ingress-nginx-controller") }
        assertThat(ingress.getStatus()).isNotNull()
        assertThat(ingress.getStatus().getLoadBalancer()).isNotNull()
        assertThat(ingress.getStatus().getLoadBalancer().getIngress()).isNotNull()
    }

    @Override
    boolean isReadyToStartTests() {
        V1PodList list = api.listPodForAllNamespaces()
                .execute()
        V1ServiceList services = api.listServiceForAllNamespaces()
                .execute()
        if (list && !list.items.isEmpty() &&
                services && !services.items.isEmpty()) {

            V1Pod argoPod = list.getItems().find { it.getMetadata().getName().startsWith("argo") }
            V1Service service = services.getItems() find { it.getMetadata().getName().startsWith("ingress") }

            if (argoPod && service) {
                return true
            }
        }
        return false
    }
}