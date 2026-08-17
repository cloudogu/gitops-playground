package com.cloudogu.gitops.infrastructure.deployment

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.MultiTenantSchema
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ArgoCdApplicationTargetResolverTest {

	@Test
	void 'resolves target for single tenant deployment'() {
		Config config = createConfig()

		def target = new ArgoCdApplicationTargetResolver(config)
			.resolve(new ContextBuilder(config).build(), 'repo-name')

		assertThat(target.applicationName).isEqualTo('foo-repo-name')
		assertThat(target.namespace).isEqualTo('foo-argocd')
		assertThat(target.project).isEqualTo('cluster-resources')
		assertThat(target.createDestinationNamespace).isTrue()
	}

	@Test
	void 'resolves target for multi tenant deployment'() {
		Config config = createConfig()
		config.multiTenant.useDedicatedInstance = true
		config.multiTenant.centralArgocdNamespace = 'central-argocd'

		def target = new ArgoCdApplicationTargetResolver(config)
			.resolve(new ContextBuilder(config).build(), 'repo-name')

		assertThat(target.applicationName).isEqualTo('foo-repo-name')
		assertThat(target.namespace).isEqualTo('central-argocd')
		assertThat(target.project).isEqualTo('foo')
		assertThat(target.createDestinationNamespace).isTrue()
	}

	@Test
	void 'disables destination namespace creation in operator mode'() {
		Config config = createConfig()
		config.features.argocd.operator = true

		def target = new ArgoCdApplicationTargetResolver(config)
			.resolve(new ContextBuilder(config).build(), 'repo-name')

		assertThat(target.createDestinationNamespace).isFalse()
	}

	private static Config createConfig() {
		return new Config(
			application: new Config.ApplicationSchema(namePrefix: 'foo-'),
			features: new Config.FeaturesSchema(argocd: new Config.ArgoCDSchema(namespace: 'argocd')),
			multiTenant: new MultiTenantSchema()
		)
	}
}
