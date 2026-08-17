package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.tools.common.HelmChartConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class RegistryToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.application.localHelmChartFolder = '/charts'
		config.registry.active = true
		config.registry.internal = true
		config.registry.namespace = 'images'
		config.registry.internalPort = 32000
		config.registry.helm.repoURL = 'https://registry.example.org'
		config.registry.helm.chart = 'registry-chart'
		config.registry.helm.version = '4.5.6'
		config.registry.helm.values = [storage: 'memory']

		RegistryToolConfig actual = new RegistryToolConfigMapper(config).map(context(config))

		assertThat(actual).isEqualTo(RegistryToolConfig.builder()
			.active(true)
			.internal(true)
			.namespace('test-images')
			.bootstrapNodePort(Config.DEFAULT_REGISTRY_PORT)
			.internalPort(32000)
			.helm(HelmChartConfig.builder()
				.repoURL('https://registry.example.org')
				.chart('registry-chart')
				.version('4.5.6')
				.values([storage: 'memory'])
				.localHelmChartFolder('/charts')
				.build())
			.build())
	}

	@Test
	void 'does not expose a namespace for an external registry'() {
		Config config = new Config()
		config.registry.internal = false

		RegistryToolConfig actual = new RegistryToolConfigMapper(config).map(context(config))

		assertThat(actual.namespace()).isNull()
	}

	private static DeploymentContext context(Config config) {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES)
	}
}
