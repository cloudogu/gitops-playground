package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ScmManagerToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.application.localHelmChartFolder = '/charts'
		config.registry.createImagePullSecrets = true
		config.registry.proxyUrl = 'proxy.example.org'
		config.registry.url = 'registry.example.org'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.readOnlyUsername = 'read-only-user'
		config.registry.username = 'registry-user'
		config.registry.proxyPassword = 'proxy-password'
		config.registry.readOnlyPassword = 'read-only-password'
		config.registry.password = 'registry-password'
		config.jenkins.active = true
		config.jenkins.urlForScm = 'http://jenkins.automation.svc'
		config.features.certManager.active = true
		config.features.certManager.issuer = 'production-issuer'
		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig()
		config.scm.scmManager.internal = true
		config.scm.scmManager.namespace = 'source-control'
		config.scm.scmManager.ingress = 'scm.example.org'
		config.scm.scmManager.username = 'scm-user'
		config.scm.scmManager.password = 'scm-password'
		config.scm.scmManager.gitOpsUsername = 'gitops-user'
		config.scm.scmManager.skipPlugins = true
		config.scm.scmManager.skipRestart = true
		config.scm.scmManager.scmmImage = 'scm-manager:custom'
		config.scm.scmManager.helm.repoURL = 'https://scm-chart.example.org'
		config.scm.scmManager.helm.chart = 'scm-chart'
		config.scm.scmManager.helm.version = '8.9.10'
		config.scm.scmManager.helm.values = [replicas: 2]

		ScmManagerToolConfig actual = new ScmManagerToolConfigMapper(config).map(context(config))

		assertThat(actual).isEqualTo(ScmManagerToolConfig.builder()
			.active(true)
			.multiTenant(true)
			.namePrefix('test-')
			.namespace('test-source-control')
			.releaseName('test-scmm')
			.ingress('scm.example.org')
			.username('scm-user')
			.password('scm-password')
			.gitOpsUsername('gitops-user')
			.skipPlugins(true)
			.skipRestart(true)
			.jenkinsActive(true)
			.jenkinsUrl('http://jenkins.automation.svc')
			.helm(HelmChartConfig.builder()
				.repoURL('https://scm-chart.example.org')
				.chart('scm-chart')
				.version('8.9.10')
				.values([replicas: 2])
				.localHelmChartFolder('/charts')
				.build())
			.imagePullSecret(ImagePullSecretConfig.builder()
				.create(true)
				.proxyUrl('proxy.example.org')
				.url('registry.example.org')
				.proxyUsername('proxy-user')
				.readOnlyUsername('read-only-user')
				.username('registry-user')
				.proxyPassword('proxy-password')
				.readOnlyPassword('read-only-password')
				.password('registry-password')
				.build())
			.templateConfig([
				features: [certManager: [active: true, issuer: 'production-issuer']],
				registry: [createImagePullSecrets: true],
				scm     : [scmManager: [scmmImage: 'scm-manager:custom']]
			])
			.build())
	}

	@Test
	void 'does not add the application prefix twice'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig(namespace: 'test-source-control')

		ScmManagerToolConfig actual = new ScmManagerToolConfigMapper(config).map(context(config))

		assertThat(actual.namespace()).isEqualTo('test-source-control')
	}

	private static DeploymentContext context(Config config) {
		return new DeploymentContext(
			DeploymentContext.TenantMode.MULTI_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.INTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES)
	}
}
