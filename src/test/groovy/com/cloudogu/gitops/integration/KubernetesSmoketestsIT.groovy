package com.cloudogu.gitops.integration


import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceList
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

/**
 * This test ensures all Pods and Namespaces are available, runnning at a startet GOP with - more or less - defaulöt values.
 */
class KubernetesSmoketestsIT {
    static String kubeConfigPath;
    CoreV1Api api


    /**
     * Gets path to kubeconfig
     */
    @BeforeAll
    static void setupKubeconfig() {
        kubeConfigPath = System.getenv("HOME") + "/.kube/config";
        if (!new File(kubeConfigPath).exists()) {
            kubeConfigPath = System.getenv("KUBECONFIG");
        }
        assertThat(kubeConfigPath) isNotBlank();
    }
    /**
     * etablish connection to kubernetes and create API to use.
     */
    @BeforeEach
    void setupConnection() {
        ApiClient client =
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();
        // set the global default api-client to the out-of-cluster one from above
        Configuration.setDefaultApiClient(client);

        // the CoreV1Api loads default api-client from global configuration.
        api = new CoreV1Api();
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
        V1Pod scmmPod = list.getItems().findAll { it.getMetadata().getName().startsWith("scmm-scm-manager") }.get(0)
        assertThat(scmmPod.getMetadata().getName()).startsWith("scmm-scm-manager")
    }

    @Test
    void ensusreNamespacesExists() {
        String expectedNamespaces = ["argocd",
                                     "cert-manager",
                                     "default",
                                     "example-apps-production",
                                     "example-apps-staging",
                                     "ingress-nginx",
                                     "kube-node-lease",
                                     "kube-public",
                                     "kube-system",
                                     "monitoring",
                                     "secrets"]

        V1NamespaceList list = api.listNamespace().execute()
        assertThat(list.getItems().containsAll()).isTrue()
    }

    /**
     * tests searches for ingress services and ensure ingress is used as laodbalancer
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


    // Kubernetes Smoketest             : 20 sek  <<< Immer
    // Jenkins Test     (e2e)           : 15 min  <<< bei Bedarf
    // ArgoCD - Feature Tests (future)  : 3 Min?  <<< Immer
    /// alles im MAIN immer
    // Profile umbennen in long-running

    // paralle Ausführung der Tests

}