package com.cloudogu.gitops.integration.tools;

import com.cloudogu.gitops.integration.TestK8sHelper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * This class checks if cert-manager is started well.
 * Cert-Manager contains own namespace ('cert-manager') which owns and 3 Pods:
 */
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
// TODO: why not in ArgoCD Operator? Clearify
public class CertManagerTestIT extends KubenetesApiTestSetup {

	String namespace = "cert-manager";

	@Override
	boolean isReadyToStartTests() {
		try {
			return TestK8sHelper.checkPodsMatchingRunningInNamespace(namespace, expectedCertManagerPods());
		} catch (AssertionError ignored) {
			return false;
		}
	}

	@BeforeAll
	static void labelTest() {
		System.out.println("###### CERT-MANAGER ######");
	}

	@Test
	void ensureNamespaceExists() {
		TestK8sHelper.waitForNamespaces(List.of(namespace));
	}

	@Test
	void ensureAllCertManagerPodsAreExist() {
		TestK8sHelper.waitForPodsMatchingRunningInNamespace(namespace, expectedCertManagerPods());
	}

	@Test
	void ensureExpectedCertManagerPodsAreRunning() {
		TestK8sHelper.waitForPodsMatchingRunningInNamespace(namespace, expectedCertManagerPods());
	}

	private static Map<String, Predicate<String>> expectedCertManagerPods() {
		Map<String, Predicate<String>> expectedPods = new LinkedHashMap<>();
		expectedPods.put(
			"cert-manager",
			podName -> podName.startsWith("cert-manager-")
				&& !podName.startsWith("cert-manager-cainjector")
				&& !podName.startsWith("cert-manager-webhook")
		);
		expectedPods.put("cert-manager-cainjector", podName -> podName.startsWith("cert-manager-cainjector"));
		expectedPods.put("cert-manager-webhook", podName -> podName.startsWith("cert-manager-webhook"));
		return expectedPods;
	}
}
