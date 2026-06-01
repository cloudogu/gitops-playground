package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.TestK8sHelper

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This tests can only be successfull, if one of theses profiles used.
 *
 * To run locally: add -Dmicronaut.environments=full to your execute configuration*/

@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|minimal|operator-full|content-examples|operator-minimal|operator-content-examples")
class ArgoCDProfileTestIT extends ProfileTestSetup {

	String namespace = 'argocd'

	@BeforeAll
	static void labelTest() {
		println "###### Integration ArgoCD test ######"
	}

	@Test
	void ensureNamespaceExists() {
		TestK8sHelper.waitForNamespaces([namespace], 40)
	}

	/**
	 * chechs that ArgoCD pods running **/
	@Test
	void ensureArgoCDIsOnlineAndPodsAreRunning() {
		String expectedPod1 = "argocd-application-controller"
		String expectedPod2 = "argocd-applicationset-controller"
		//        String expectedPod3 = "argocd-notifications-controller" // not stable
		String expectedPod4 = "argocd-redis"
		String expectedPod5 = "argocd-repo-server"
		String expectedPod6 = "argocd-server"

		List<String> expectedPods = [expectedPod1, expectedPod2, /* expectedPod3,*/ expectedPod4, expectedPod5, expectedPod6,]

		TestK8sHelper.waitForPodPrefixesRunningInNamespace(namespace, expectedPods, 40)
	}
}