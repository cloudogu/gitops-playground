package com.cloudogu.gitops

import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.ScmTenantSchema

import io.micronaut.context.ApplicationContext

import org.junit.jupiter.api.Test

class ApplicationTest {

	private Config config = new Config()

	@Test
	void 'feature\'s ordering is correct'() {
		def application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application)
		def features = application.features.collect { it.class.simpleName }

		assertThat(features).isEqualTo(['Registry', 'GitHandler', 'Jenkins', 'ArgoCD', 'Ingress', 'CertManager', 'Mail', 'Monitoring', 'ExternalSecretsOperator', 'Vault', 'ContentLoader'])
	}

	@Test
	void 'get active namespaces correctly'() {
		config.registry.active = true
		config.jenkins.active = true
		config.features.monitoring.active = true
		config.features.argocd.active = true
		config.content.examples = true
		config.features.ingress.active = true
		config.application.namePrefix = 'test1-'
		config.content.namespaces = ['${config.application.namePrefix}example-apps-staging',
		                             '${config.application.namePrefix}example-apps-production']
		List<String> namespaceList = new ArrayList<>(Arrays.asList("test1-argocd",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-" + config.features.ingress.ingressNamespace,
			"test1-monitoring",
			"test1-registry",
			"test1-jenkins"))
		def application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application)
		application.setNamespaceListToConfig(config)
		assertThat(config.application.namespaces.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
	}

	@Test
	void 'get active namespaces correctly in Openshift'() {
		config.registry.active = true
		config.jenkins.active = true
		config.features.monitoring.active = true
		config.features.argocd.active = true
		config.content.examples = true
		config.features.ingress.active = true
		config.application.namePrefix = 'test1-'
		config.application.openshift = true
		config.content.namespaces = ['${config.application.namePrefix}example-apps-staging',
		                             '${config.application.namePrefix}example-apps-production']
		List<String> namespaceList = new ArrayList<>(Arrays.asList("test1-argocd",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-" + config.features.ingress.ingressNamespace,
			"test1-monitoring",
			"test1-registry",
			"test1-jenkins"))
		def application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application)
		application.setNamespaceListToConfig(config)
		assertThat(config.application.namespaces.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
	}

	@Test
	void 'handles content namespaces without template'() {
		config.content.namespaces = ['example-apps-staging',
		                             'example-apps-production']
		def application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application)
		application.setNamespaceListToConfig(config)
		assertThat(config.application.namespaces.getActiveNamespaces()).containsAll(["example-apps-staging",
		                                                                             "example-apps-production",])
	}

	@Test
	void 'handles empty content namespaces'() {
		def application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application)
		application.setNamespaceListToConfig(config)
		// No exception == happy
	}

	@Test
	void 'get active namespaces correctly in Openshift if jenkins and scm are external'() {
		config.registry.active = true
		config.jenkins.active = true
		config.jenkins.internal = false
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig()
		config.scm.scmManager.internal = false
		config.features.monitoring.active = true
		config.features.argocd.active = true
		config.content.examples = true
		config.features.ingress.active = true
		config.application.namePrefix = 'test1-'
		config.application.openshift = true
		config.content.namespaces = ['${config.application.namePrefix}example-apps-staging',
		                             '${config.application.namePrefix}example-apps-production']
		List<String> namespaceList = new ArrayList<>(Arrays.asList("test1-argocd",
			"test1-example-apps-staging",
			"test1-example-apps-production",
			"test1-" + config.features.ingress.ingressNamespace,
			"test1-monitoring",
			"test1-registry",))
		def application = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Application)
		application.setNamespaceListToConfig(config)
		assertThat(config.application.namespaces.getActiveNamespaces()).containsExactlyInAnyOrderElementsOf(namespaceList)
	}
}