package com.cloudogu.gitops.integration


import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceList
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class PrometheusStackTestIT {

    @Test
    void testtesttest() {
        assertThat(true).isEqualTo(true)


    }

    @Test
    void ensureJenkinsPodIsStarted() {

        String kubeConfigPath = System.getenv("HOME") + "/.kube/config";

        if (!new File(kubeConfigPath).exists()) {
            kubeConfigPath = System.getenv("KUBECONFIG");
        }
        System.out.println("Kubeconfig" + kubeConfigPath)
        assertThat(kubeConfigPath) isNotBlank();

        // loading the out-of-cluster config, a kubeconfig from file-system
        ApiClient client =
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();
        // set the global default api-client to the out-of-cluster one from above
        Configuration.setDefaultApiClient(client);

        // the CoreV1Api loads default api-client from global configuration.
        CoreV1Api api = new CoreV1Api();

        V1PodList list = api.listPodForAllNamespaces()
                .execute();
        // invokes the CoreV1Api client
        V1Pod jenkinsPod = list.getItems().findAll { it.getMetadata().getName().startsWith("jenkins") }.get(0)

        assertThat(jenkinsPod.getMetadata().getName()).isEqualTo("jenkins-0")
    }

    @Test
    void checknamesOverAPI() {

        String kubeConfigPath = System.getenv("HOME") + "/.kube/config";
        if (!new File(kubeConfigPath).exists()) {
            kubeConfigPath = System.getenv("KUBECONFIG");
        }
        assertThat(kubeConfigPath) isNotBlank();

        // loading the out-of-cluster config, a kubeconfig from file-system
        ApiClient client =
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();

        // set the global default api-client to the out-of-cluster one from above
        Configuration.setDefaultApiClient(client);

        // the CoreV1Api loads default api-client from global configuration.
        CoreV1Api api = new CoreV1Api();

        // invokes the CoreV1Api client

        V1NamespaceList list = api.listNamespace().execute()
        for (def item : list.getItems()) {
            System.out.println(item.getMetadata().getName())
        }
    }
}
