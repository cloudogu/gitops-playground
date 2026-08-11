package com.cloudogu.gitops.tools.core

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class JenkinsToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.application.namePrefixForEnvVars = 'TEST_'
		config.application.localHelmChartFolder = '/charts'
		config.application.runningInsideK8s = true
		config.application.trace = true
		config.application.insecure = true
		config.application.baseUrl = 'example.org'
		config.registry.url = 'registry.example.org'
		config.registry.path = 'images'
		config.registry.username = 'registry-user'
		config.registry.password = 'registry-password'
		config.registry.twoRegistries = true
		config.registry.proxyUrl = 'proxy.example.org'
		config.registry.proxyPath = 'proxy-images'
		config.registry.proxyUsername = 'proxy-user'
		config.registry.proxyPassword = 'proxy-password'
		config.registry.readOnlyUsername = 'read-only-user'
		config.registry.readOnlyPassword = 'read-only-password'
		config.registry.createImagePullSecrets = true
		config.jenkins.active = true
		config.jenkins.internal = true
		config.jenkins.namespace = 'automation'
		config.jenkins.url = 'https://jenkins.example.org'
		config.jenkins.username = 'jenkins-user'
		config.jenkins.password = 'jenkins-password'
		config.jenkins.metricsUsername = 'metrics-user'
		config.jenkins.metricsPassword = 'metrics-password'
		config.jenkins.skipRestart = true
		config.jenkins.skipPlugins = true
		config.jenkins.mavenCentralMirror = 'https://maven.example.org'
		config.jenkins.internalBashImage = 'bash:custom'
		config.jenkins.internalDockerClientVersion = '28.0.0'
		config.jenkins.jenkinsImage = 'jenkins:custom'
		config.jenkins.ingress = 'jenkins-ingress.example.org'
		config.jenkins.additionalEnvs = [FIRST: 'one', SECOND: 'two']
		config.jenkins.oidc.issuerUrl = 'https://id.example.org'
		config.jenkins.oidc.clientId = 'jenkins-client'
		config.jenkins.oidc.clientSecret = 'jenkins-client-secret'
		config.jenkins.helm.repoURL = 'https://jenkins-chart.example.org'
		config.jenkins.helm.chart = 'jenkins-chart'
		config.jenkins.helm.version = '7.8.9'
		config.jenkins.helm.values = [controller: [replicas: 2]]
		config.features.argocd.active = true
		config.features.monitoring.active = true
		config.features.certManager.active = true
		config.features.certManager.issuer = 'production-issuer'
		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig(password: 'scmm-password')
		config.scm.gitlab = new ScmTenantSchema.GitlabTenantConfig(
			username: 'gitlab-user', password: 'gitlab-password')

		JenkinsToolConfig actual = new JenkinsToolConfigMapper().map(context(config))

		assertThat(actual).isEqualTo(JenkinsToolConfig.builder()
			.active(true)
			.internal(true)
			.namespace('test-automation')
			.application(JenkinsToolConfig.Application.builder()
				.namePrefix('test-')
				.environmentPrefix('TEST_')
				.runningInsideK8s(true)
				.trace(true)
				.insecure(true)
				.build())
			.server(JenkinsToolConfig.Server.builder()
				.url('https://jenkins.example.org')
				.username('jenkins-user')
				.password('jenkins-password')
				.metricsUsername('metrics-user')
				.metricsPassword('metrics-password')
				.skipRestart(true)
				.skipPlugins(true)
				.mavenCentralMirror('https://maven.example.org')
				.internalBashImage('bash:custom')
				.oidcConfigured(true)
				.additionalEnvironments([FIRST: 'one', SECOND: 'two'])
				.build())
			.scm(JenkinsToolConfig.Scm.builder()
				.providerType(ScmProviderType.SCM_MANAGER)
				.scmManagerPassword('scmm-password')
				.gitlabUsername('gitlab-user')
				.gitlabPassword('gitlab-password')
				.build())
			.registry(JenkinsToolConfig.Registry.builder()
				.url('registry.example.org')
				.path('images')
				.username('registry-user')
				.password('registry-password')
				.twoRegistries(true)
				.proxyUrl('proxy.example.org')
				.proxyPath('proxy-images')
				.proxyUsername('proxy-user')
				.proxyPassword('proxy-password')
				.build())
			.argocdActive(true)
			.monitoringActive(true)
			.helm(HelmChartConfig.builder()
				.repoURL('https://jenkins-chart.example.org')
				.chart('jenkins-chart')
				.version('7.8.9')
				.values([controller: [replicas: 2]])
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
				application: [baseUrl: 'example.org'],
				features   : [certManager: [active: true, issuer: 'production-issuer']],
				jenkins    : [
					helm                       : [version: '7.8.9'],
					ingress                    : 'jenkins-ingress.example.org',
					internalBashImage           : 'bash:custom',
					internalDockerClientVersion : '28.0.0',
					jenkinsImage                : 'jenkins:custom',
					oidc                        : config.jenkins.oidc,
					password                    : 'jenkins-password',
					url                         : 'https://jenkins.example.org',
					username                    : 'jenkins-user'
				],
				registry   : [createImagePullSecrets: true]
			])
			.build())
	}

	@Test
	void 'does not expose a namespace for an external Jenkins'() {
		Config config = new Config()
		config.jenkins.internal = false

		JenkinsToolConfig actual = new JenkinsToolConfigMapper().map(context(config))

		assertThat(actual.namespace()).isNull()
	}

	private static DeploymentContext context(Config config) {
		return new DeploymentContext(
			config,
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES)
	}
}
