package com.cloudogu.gitops.integration.features

import com.cloudogu.gitops.utils.CommandExecutor
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Namespace
import io.kubernetes.client.openapi.models.V1NamespaceList
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

/**
 * This class checks if Prometheus is started well.
 * Prometheus contains own namespace ('monitoinr') which owns and 3 Pods:
 *  - Grafana
 *  - Operator
 *  - prometheus-stack
 */
class PrometheusStackTestIT extends FeatureTestSetup {

    String namespace = 'monitoring'
    String grafanaPod = 'prometheus-stack-grafana'
    String operatorPod = 'prometheus-stack-operator'
    String prometheusPod = 'prometheus-stack-prometheus'

    @Test
    void ensureNamespaceExists() {
        def namespaces = api.listNamespace().execute()
        assertThat(namespaces).isNotNull()
        assertThat(namespaces.getItems().isEmpty()).isFalse()
        def namespace = namespaces.getItems().find { namespace.equals(it.getMetadata().name) }
        assertThat(namespace).isNotNull()

    }

    @Test
    void ensureGrafanaIsStarted() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods).isNotNull()
        assertThat(pods.getItems().isEmpty()).isFalse()

        def grafanaPod = pods.items.find { it.getMetadata().name.contains(grafanaPod) }
        assertThat(grafanaPod).isNotNull()
        assertThat(grafanaPod.status.phase).isEqualTo("Running")
    }

    @Test
    void ensureOperatorIsStarted() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods).isNotNull()
        assertThat(pods.getItems().isEmpty()).isFalse()

        def operator = pods.items.find { it.getMetadata().name.contains(operatorPod) }
        assertThat(operator).isNotNull()
        assertThat(operator.status.phase).isEqualTo("Running")
    }
    @Test
    void ensurePrometheusStackIsStarted() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods).isNotNull()
        assertThat(pods.getItems().isEmpty()).isFalse()

        def prometheus = pods.items.find { it.getMetadata().name.contains(prometheusPod) }
        assertThat(prometheus).isNotNull()
        assertThat(prometheus.status.phase).isEqualTo("Running")
    }

    @Test
    void ensureNamespaceGot3Pods() {
        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods.getItems().size()).isEqualTo(3)
    }
}
