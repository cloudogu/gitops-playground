package com.cloudogu.gitops.integration.features

import com.cloudogu.gitops.integration.TestK8sHelper

import groovy.util.logging.Slf4j

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This class checks if cert-manager is started well.
 * Cert-Manager contains own namespace ('cert-manager') which owns and 3 Pods:*/
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full")
//TODO: why not in ArgoCD Operator? Clearify
class CertManagerTestIT extends KubenetesApiTestSetup {

	String namespace = 'cert-manager'

	@Override
	boolean isReadyToStartTests() {
		try {
			return TestK8sHelper.checkPodsMatchingRunningInNamespace(namespace, expectedCertManagerPods())
		} catch (AssertionError ignored) {
			return false
		}
	}

	@BeforeAll
	static void labelTest() {
		println "###### CERT-MANAGER ######"
	}

	@Test
	void ensureNamespaceExists() {
		TestK8sHelper.waitForNamespaces([namespace])
	}

	@Test
	void ensureAllCertManagerPodsAreExist() {
		TestK8sHelper.waitForPodsMatchingRunningInNamespace(namespace, expectedCertManagerPods())
	}

	@Test
	void ensureExpectedCertManagerPodsAreRunning() {
		TestK8sHelper.waitForPodsMatchingRunningInNamespace(namespace, expectedCertManagerPods())
	}

	private static Map<String, Closure<Boolean>> expectedCertManagerPods() {
		[
			'cert-manager'          : { String podName ->
				podName.startsWith('cert-manager-') &&
					!podName.startsWith('cert-manager-cainjector') &&
					!podName.startsWith('cert-manager-webhook')
			},
			'cert-manager-cainjector': { String podName -> podName.startsWith('cert-manager-cainjector') },
			'cert-manager-webhook'  : { String podName -> podName.startsWith('cert-manager-webhook') },
		]
	}
}