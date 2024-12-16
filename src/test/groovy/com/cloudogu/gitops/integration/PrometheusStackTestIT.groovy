package com.cloudogu.gitops.integration

import com.cloudogu.gitops.jenkins.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.extended.kubectl.Kubectl
import org.junit.jupiter.api.Test
import static org.assertj.core.api.Assertions.assertThat
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;

class PrometheusStackTestIT {

    @Test
    void testtesttest()  {
        assertThat(true).isEqualTo(false)


    }

    @Test
    void kubectltesttest()  {

        String kubeConfigPath = System.getenv("HOME") + "/.kube/config";

        // loading the out-of-cluster config, a kubeconfig from file-system
        ApiClient client =
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();

        Kubectl.apply()
        // set the global default api-client to the out-of-cluster one from above
        Configuration.setDefaultApiClient(client);

        // the CoreV1Api loads default api-client from global configuration.
        CoreV1Api api = new CoreV1Api();

        // invokes the CoreV1Api client
        V1PodList list =
                api.listPodForAllNamespaces()
        for (V1Pod item : list.getItems()) {
            System.out.println(item.getMetadata().getName())
        }
    }
}
