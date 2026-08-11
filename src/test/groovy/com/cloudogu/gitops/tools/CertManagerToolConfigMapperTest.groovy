package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class CertManagerToolConfigMapperTest {

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
		config.features.certManager.active = true
		config.features.certManager.namespace = 'certificates'
		config.features.certManager.issuer = 'production-issuer'
		config.features.certManager.helm.repoURL = 'https://cert.example.org'
		config.features.certManager.helm.chart = 'cert-chart'
		config.features.certManager.helm.version = '1.2.3'
		config.features.certManager.helm.values = [replicas: 2]
		config.features.certManager.helm.image = 'cert-image'
		config.features.certManager.helm.webhookImage = 'webhook-image'
		config.features.certManager.helm.cainjectorImage = 'cainjector-image'
		config.features.certManager.helm.acmeSolverImage = 'solver-image'
		config.features.certManager.helm.startupAPICheckImage = 'startup-image'

		CertManagerToolConfig actual = new CertManagerToolConfigMapper().map(context(config))

		assertThat(actual).isEqualTo(CertManagerToolConfig.builder()
			.active(true)
			.namespace('test-certificates')
			.helm(HelmChartConfig.builder()
				.repoURL('https://cert.example.org')
				.chart('cert-chart')
				.version('1.2.3')
				.values([replicas: 2])
				.localHelmChartFolder('/charts')
				.build())
			.imagePullSecret(imagePullSecret())
			.templateConfig([
				application: [podResources: true, skipCrds: true],
				features   : [certManager: [
					issuer: 'production-issuer',
					helm  : [
						image                : 'cert-image',
						webhookImage         : 'webhook-image',
						cainjectorImage      : 'cainjector-image',
						acmeSolverImage      : 'solver-image',
						startupAPICheckImage : 'startup-image'
					]
				]],
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
