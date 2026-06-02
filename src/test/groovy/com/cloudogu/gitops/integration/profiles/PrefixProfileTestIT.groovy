package com.cloudogu.gitops.integration.profiles

import static org.assertj.core.api.Assertions.fail

import com.cloudogu.gitops.integration.TestK8sHelper

import java.util.concurrent.TimeUnit
import groovy.util.logging.Slf4j

import org.awaitility.core.ConditionTimeoutException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * This tests can only be successfull, if one of theses profiles used.
 * * To run locally: add -Dmicronaut.environments=full-prefix to your execute configuration*/
@Slf4j
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full-prefix")
class PrefixProfileTestIT extends ProfileTestSetup {
	// is used for pre-condition
	static String exampleStagingNs = 'my-prefix-example-apps-staging'
	static String argocdNs = 'my-prefix-argocd'
	String scmManagerNs = 'my-prefix-scm-manager'
	String registryNs = 'my-prefix-registry'
	String ingressNs = 'my-prefix-ingress'
	/* Jenking can not start ingress*/
	static String certManagerNs = 'my-prefix-cert-manager'
	String jenkinsNs = 'my-prefix-jenkins'
	static String monitoringNs = 'my-prefix-monitoring'
	String secretsNs = 'my-prefix-secrets'
	String exampleProductionNs = 'my-prefix-example-apps-production'

	@BeforeAll
	static void labelTest() {
		log.info "###### Integration test for Prefix ######"

		try {
			TestK8sHelper.waitForAllPodsRunningInNamespace(exampleStagingNs, "", 40, TimeUnit.MINUTES)
		} catch (ConditionTimeoutException timeoutEx) {
			TestK8sHelper.dumpNamespacesAndPods()
			fail('Cluster not ready, sth false.', timeoutEx)
		}
	}

	@Test
	void ensureNamespacesExistWithPrefix() {
		List<String> expectedNamespaces = [argocdNs,
		                                   scmManagerNs,
		                                   registryNs,
		                                   ingressNs,
		                                   certManagerNs,
		                                   jenkinsNs,
		                                   monitoringNs,
		                                   secretsNs,
		                                   exampleProductionNs,
		                                   exampleStagingNs]

		TestK8sHelper.waitForNamespaces(expectedNamespaces)
	}

	@Test
	void ensurePodsAreRunningInPrefixedNamespaces() {
		List<String> namespacesToCheck = [argocdNs,
		                                  scmManagerNs,
		                                  registryNs,
		                                  certManagerNs,
		                                  monitoringNs]
		namespacesToCheck.each { String ns ->
			TestK8sHelper.waitForAllPodsRunningInNamespace(ns)
		}
	}
}