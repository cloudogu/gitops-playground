package com.cloudogu.gitops.kubernetes

import groovy.util.logging.Slf4j
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import lombok.Getter
import lombok.Setter

import java.time.Duration

@Slf4j
@Singleton
@Setter
@Getter
class KubernetesApiClient {

    String kubeConfigPath=System.getenv("HOME") + "/.kube/config"

    CoreV1Api api
    int TIME_TO_WAIT = 12
    int RETRY_SECONDS = 15

    public init(){
        setupKubeconfig()
        setupConnection()
    }

    private void setupKubeconfig() throws FileNotFoundException {
        if (!new File(kubeConfigPath).exists()) {
            kubeConfigPath = System.getenv("KUBECONFIG")
            if(!kubeConfigPath){
                throw new FileNotFoundException("Kubeconfig file not found at default path and KUBECONFIG environment variable is not set or invalid.")
            }
        }
    }

    void setupConnection() {
        ApiClient client =
                ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build()
        // set the global default api-client to the out-of-cluster one from above
        Configuration.setDefaultApiClient(client)

        // the CoreV1Api loads default api-client from global configuration.
        api = new CoreV1Api()
        waitForCondition(() ->
                waitingCondition(),
                maxWaitTimeInMinutes(TIME_TO_WAIT),
                pollIntervallSeconds(RETRY_SECONDS)
        )
    }



    private static Duration pollIntervallSeconds(int time) {
        return Duration.ofSeconds(time)
    }

    private static Duration maxWaitTimeInMinutes(int time) {
        return Duration.ofMinutes(time)
    }
}