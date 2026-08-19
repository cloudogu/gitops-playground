package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ExternalSecretsOperatorToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.application.localHelmChartFolder = '/charts'
		config.application.podResources = true
		config.application.skipCrds = true
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy.example.org'
		config.registry.url = 'registry.example.org'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.readOnlyUsername = 'read-only-user'
		config.registry.username = 'registry-user'
		config.registry.proxyPassword = 'proxy-password'
		config.registry.readOnlyPassword = 'read-only-password'
		config.registry.password = 'registry-password'
		config.features.secrets.active = true
		config.features.secrets.namespace = 'external-secrets'
		config.features.secrets.externalSecrets.helm.repoURL = 'https://eso.example.org'
		config.features.secrets.externalSecrets.helm.chart = 'eso-chart'
		config.features.secrets.externalSecrets.helm.version = '2.3.4'
		config.features.secrets.externalSecrets.helm.values = [replicas: 3]
		config.features.secrets.externalSecrets.helm.image = 'eso-image'
		config.features.secrets.externalSecrets.helm.certControllerImage = 'cert-controller-image'
		config.features.secrets.externalSecrets.helm.webhookImage = 'webhook-image'

		ExternalSecretsOperatorToolConfig actual = new ExternalSecretsOperatorToolConfigMapper(config).map(context())

		assertThat(actual).isEqualTo(ExternalSecretsOperatorToolConfig.builder()
			.active(true)
			.namespace('test-external-secrets')
			.helm(HelmChartConfig.builder()
				.repoURL('https://eso.example.org')
				.chart('eso-chart')
				.version('2.3.4')
				.values([replicas: 3])
				.localHelmChartFolder('/charts')
				.build())
			.imagePullSecret(imagePullSecret())
			.templateConfig([
				application: [podResources: true, skipCrds: true],
				features   : [secrets: [externalSecrets: [helm: [
					image               : 'eso-image',
					certControllerImage : 'cert-controller-image',
					webhookImage        : 'webhook-image'
				]]]],
				registry   : [createImagePullSecrets: true]
			])
			.build())
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
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
