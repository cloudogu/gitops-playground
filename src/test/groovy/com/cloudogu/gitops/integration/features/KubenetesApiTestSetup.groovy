package com.cloudogu.gitops.integration.features

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail

abstract class KubenetesApiTestSetup {
    static String kubeConfigPath;
    CoreV1Api api
    int TIME_TO_WAIT = 7;
    int RETRY_SECONDS = 15

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
        waitForCondition(() ->
                waitingCondition(),
                timeToWaitMaxInMinutes(TIME_TO_WAIT),
                inSecondsRepeatPollIn(RETRY_SECONDS)
        )
    }

    static void waitForCondition(Supplier<Boolean> condition, Duration timeout, Duration pollInterval) {
        Instant end = Instant.now().plus(timeout);
        while (Instant.now().isBefore(end)) {
            if (condition.get()) {
                return; // Bedingung erfüllt
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling unterbrochen", e);
            }
        }
        fail('Wait condition not fulfilled in time');
    }

    private Duration inSecondsRepeatPollIn(int time) {
        return Duration.ofSeconds(time)
    }

    private Duration timeToWaitMaxInMinutes(int time) {
        return Duration.ofMinutes(time)
    }

    boolean waitingCondition() {
        println 'waiting for pods'
        return isReadyToStartTests()
    }
    /**
     * This condition is to override, if test has to wait, i.e. ArgoCD has to do its GitOps magic.
     * @return
     */

    abstract boolean isReadyToStartTests()
}
