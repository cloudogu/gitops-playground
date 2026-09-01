package com.cloudogu.gitops.integration.tools;

import com.cloudogu.gitops.integration.TestK8sHelper;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class checks if Prometheus is started well.
 * Prometheus contains own namespace ('monitoring') which owns and 3 Pods:
 *  - Grafana
 *  - Operator
 *  - prometheus-stack
 */
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
public class MonitoringTestIT extends KubenetesApiTestSetup {

	String namespace = "monitoring";
	String grafanaPod = "kube-prometheus-stack-grafana";
	String operatorPod = "kube-prometheus-stack-operator";
	String prometheusPod = "prometheus-kube-prometheus-stack-prometheus";

	@Override
	boolean isReadyToStartTests() {
		try {
			return TestK8sHelper.checkAllPodsRunningInNamespace(namespace, grafanaPod);
		} catch (AssertionError ignored) {
			return false;
		}
	}

	@BeforeAll
	static void labelTest() {
		System.out.println("###### PROMETHEUS ######");
	}

	@Test
	void ensureNamespaceExists() {
		TestK8sHelper.waitForNamespaces(List.of(namespace));
	}

	@Test
	void ensureGrafanaIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespace, grafanaPod);
	}

	@Test
	void ensureOperatorIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespace, operatorPod);
	}

	@Disabled("not start on jenkins")
	@Test
	void ensureMonitoringIsStarted() throws ApiException {
		V1PodList pods = api.listNamespacedPod(namespace).execute();
		assertThat(pods).isNotNull();
		assertThat(pods.getItems().isEmpty()).isFalse();

		V1Pod prometheus = null;
		for (V1Pod pod : pods.getItems()) {
			if (pod.getMetadata().getName().contains(prometheusPod)) {
				prometheus = pod;
				break;
			}
		}
		assertThat(prometheus).isNotNull();
		assertThat(prometheus.getStatus().getPhase()).isEqualTo("Running");
	}

	@Disabled("jenkins got only 2")
	@Test
	void ensureNamespaceGot3Pods() throws ApiException {
		V1PodList pods = api.listNamespacedPod(namespace).execute();
		assertThat(pods.getItems().size()).isEqualTo(3);
	}
}
