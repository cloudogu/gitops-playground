package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class IngressToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.application.localHelmChartFolder = '/charts'
		config.application.netpols = true
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy.example.org'
		config.registry.url = 'registry.example.org'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.readOnlyUsername = 'read-only-user'
		config.registry.username = 'registry-user'
		config.registry.proxyPassword = 'proxy-password'
		config.registry.readOnlyPassword = 'read-only-password'
		config.registry.password = 'registry-password'
		config.features.ingress.active = true
		config.features.ingress.ingressNamespace = 'gateway'
		config.features.ingress.helm.repoURL = 'https://ingress.example.org'
		config.features.ingress.helm.chart = 'ingress-chart'
		config.features.ingress.helm.version = '3.4.5'
		config.features.ingress.helm.values = [replicas: 4]
		config.features.ingress.helm.image = 'ingress-image'
		config.features.monitoring.active = true
		config.features.monitoring.namespace = 'observability'

		IngressToolConfig actual = new IngressToolConfigMapper(config).map(context(config))

		assertThat(actual).isEqualTo(IngressToolConfig.builder()
			.active(true)
			.namespace('test-gateway')
			.helm(HelmChartConfig.builder()
				.repoURL('https://ingress.example.org')
				.chart('ingress-chart')
				.version('3.4.5')
				.values([replicas: 4])
				.localHelmChartFolder('/charts')
				.build())
			.imagePullSecret(imagePullSecret())
			.templateConfig([
				application: [namePrefix: 'test-', netpols: true],
				features   : [
					ingress   : [helm: [image: 'ingress-image']],
					monitoring: [active: true, namespace: 'observability']
				],
				registry   : [createImagePullSecrets: true]
			])
			.build())
	}

	private static DeploymentContext context(Config config) {
		return new DeploymentContext(
			config,
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES)
	}

	private static ImagePullSecretConfig imagePullSecret() {
		return ImagePullSecretConfig.builder()
			.create(true)
			.proxyUrl('proxy.example.org')
			.url('registry.example.org')
			.proxyUsername('proxy-user')
			.readOnlyUsername('read-only-user')
			.username('registry-user')
			.proxyPassword('proxy-password')
			.readOnlyPassword('read-only-password')
			.password('registry-password')
			.build()
	}
}
