package com.cloudogu.gitops.integration.features

import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.integration.TestK8sHelper

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This class checks if Prometheus is started well.
 * Prometheus contains own namespace ('monitoring') which owns and 3 Pods:
 *  - Grafana
 *  - Operator
 *  - prometheus-stack*/
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
class MonitoringTestIT extends KubenetesApiTestSetup {

	String namespace = 'monitoring'
	String grafanaPod = 'kube-prometheus-stack-grafana'
	String operatorPod = 'kube-prometheus-stack-operator'
	String prometheusPod = 'prometheus-kube-prometheus-stack-prometheus'

	@Override
	boolean isReadyToStartTests() {
		try {
			return TestK8sHelper.checkAllPodsRunningInNamespace(namespace, grafanaPod)
		} catch (AssertionError ignored) {
			return false
		}
	}

	@BeforeAll
	static void labelTest() {
		println "###### PROMETHEUS ######"
	}

	@Test
	void ensureNamespaceExists() {
		TestK8sHelper.waitForNamespaces([namespace])
	}

	@Test
	void ensureGrafanaIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespace, grafanaPod)
	}

	@Test
	void ensureOperatorIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespace, operatorPod)
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