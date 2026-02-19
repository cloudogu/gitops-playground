package com.cloudogu.gitops.integration.features


import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import static org.assertj.core.api.Assertions.assertThat

/**
 * This class checks if Prometheus is started well.
 * Prometheus contains own namespace ('monitoring') which owns and 3 Pods:
 *  - Grafana
 *  - Operator
 *  - prometheus-stack
 */
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
class MonitoringTestIT extends KubenetesApiTestSetup {

    String namespace = 'monitoring'
    String grafanaPod = 'prometheus-stack-grafana'
    String operatorPod = 'prometheus-stack-operator'
    String prometheusPod = 'prometheus-stack-prometheus'

    @Override
    boolean isReadyToStartTests() {

        def pods = api.listNamespacedPod(namespace).execute()
        if (pods && !pods.items.isEmpty()) {
            def grafanaPod = pods.items.find { it.getMetadata().name.contains(grafanaPod) }
            if (grafanaPod) {
                return "Running".equals(grafanaPod.status.phase)
            }
        }
        return false;
    }

    @BeforeAll
    static void labelTest() {
        println "###### PROMETHEUS ######"
    }

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

    @Disabled("not start on jenkins")
    @Test
    void ensureMonitoringIsStarted() {

        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods).isNotNull()
        assertThat(pods.getItems().isEmpty()).isFalse()

        def prometheus = pods.items.find { it.getMetadata().name.contains(prometheusPod) }
        assertThat(prometheus).isNotNull()
        assertThat(prometheus.status.phase).isEqualTo("Running")
    }
    @Disabled("jenkins got only 2")
    @Test
    void ensureNamespaceGot3Pods() {
        def pods = api.listNamespacedPod(namespace).execute()
        assertThat(pods.getItems().size()).isEqualTo(3)
    }
}