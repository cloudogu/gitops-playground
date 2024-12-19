package com.cloudogu.gitops.integration.features

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

import static org.assertj.core.api.Assertions.assertThat

class FeatureTestSetup {
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
}
