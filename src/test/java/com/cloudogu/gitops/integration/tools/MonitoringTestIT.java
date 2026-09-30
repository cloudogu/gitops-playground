package com.cloudogu.gitops.integration.tools;

import com.cloudogu.gitops.integration.TestK8sHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

/**
 * This class checks if Prometheus is started well.
 * Prometheus uses its own namespace ('monitoring'). The tests verify that the core monitoring pods
 * for Grafana, the Prometheus Operator, and Prometheus are running.
 */
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|full-netpols|operator-full")
public class MonitoringTestIT extends KubernetesApiTestSetup {

	String namespace = "monitoring";
	String grafanaPod = "kube-prometheus-stack-grafana";
	String operatorPod = "kube-prometheus-stack-operator";
	String prometheusPod = "prometheus-kube-prometheus-stack-prometheus";

	@Override
	boolean isReadyToStartTests() {
		try {
			return TestK8sHelper.checkAllPodContainersReadyInNamespace(namespace, grafanaPod);
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
		TestK8sHelper.waitForAllPodContainersReadyInNamespace(namespace, grafanaPod);
	}

	@Test
	void ensureOperatorIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespace, operatorPod);
	}

	@Test
	void ensureMonitoringIsStarted() {
		TestK8sHelper.waitForAllPodsRunningInNamespace(namespace, prometheusPod);
	}
}
