package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class MonitoringToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'test-'
		config.application.localHelmChartFolder = '/charts'
		config.application.namespaces.dedicatedNamespaces = ['jenkins', 'monitoring'] as LinkedHashSet
		config.application.namespaces.tenantNamespaces = ['team-a', 'team-b'] as LinkedHashSet
		config.application.namespaceIsolation = true
		config.application.netpols = true
		config.application.skipCrds = true
		// Intentionally differs from the DeploymentContext to verify derived values come from the context.
		config.application.openshift = false
		config.application.podResources = true
		config.application.password = 'application-password'
		config.application.username = 'application-user'
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
		config.jenkins.internal = false
		config.jenkins.namespace = 'jenkins-system'
		config.jenkins.url = 'https://jenkins.example.org'
		config.jenkins.metricsUsername = 'jenkins-metrics-user'
		config.jenkins.metricsPassword = 'jenkins-metrics-password'
		config.features.ingress.active = true
		config.features.certManager.active = true
		config.features.certManager.issuer = 'production-issuer'
		config.features.mail.active = true
		config.features.mail.smtpAddress = 'smtp.example.org'
		config.features.mail.smtpPort = 2525
		config.features.mail.smtpUser = 'smtp-user'
		config.features.mail.smtpPassword = 'smtp-password'
		config.features.monitoring.active = true
		config.features.monitoring.namespace = 'observability'
		config.features.monitoring.grafanaUrl = 'https://grafana.example.org'
		config.features.monitoring.grafanaEmailFrom = 'grafana@example.org'
		config.features.monitoring.grafanaEmailTo = 'team@example.org'
		config.features.monitoring.oidc.clientId = 'grafana-client'
		config.features.monitoring.helm.repoURL = 'https://monitoring.example.org'
		config.features.monitoring.helm.chart = 'monitoring-chart'
		config.features.monitoring.helm.version = '6.7.8'
		config.features.monitoring.helm.values = [retention: '30d']
		config.features.monitoring.helm.grafanaImage = 'grafana-image'
		config.features.monitoring.helm.grafanaSidecarImage = 'sidecar-image'
		config.features.monitoring.helm.prometheusImage = 'prometheus-image'
		config.features.monitoring.helm.prometheusOperatorImage = 'operator-image'
		config.features.monitoring.helm.prometheusConfigReloaderImage = 'reloader-image'
		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig(namespace: 'source-control')

		MonitoringToolConfig actual = new MonitoringToolConfigMapper(config).map(context(config))

		assertThat(actual).isEqualTo(MonitoringToolConfig.builder()
			.active(true)
			.namespace('test-observability')
			.namePrefix('test-')
			.activeNamespaces(['jenkins', 'monitoring', 'team-a', 'team-b'])
			.namespaceIsolation(true)
			.netpols(true)
			.skipCrds(true)
			.openshift(true)
			.airgapped(true)
			.applicationPassword('application-password')
			.jenkinsMetricsPassword('jenkins-metrics-password')
			.smtpUser('smtp-user')
			.smtpPassword('smtp-password')
			.grafanaUrl('https://grafana.example.org')
			.jenkinsInternal(false)
			.jenkinsNamespace('jenkins-system')
			.jenkinsUrl('https://jenkins.example.org')
			.jenkinsMetricsUsername('jenkins-metrics-user')
			.ingressActive(true)
			.jenkinsActive(true)
			.helm(HelmChartConfig.builder()
				.repoURL('https://monitoring.example.org')
				.chart('monitoring-chart')
				.version('6.7.8')
				.values([retention: '30d'])
				.localHelmChartFolder('/charts')
				.build())
			.imagePullSecret(imagePullSecret())
			.templateConfig([
				application: [
					namePrefix        : 'test-',
					namespaceIsolation: true,
					openshift         : true,
					podResources      : true,
					skipCrds          : true,
					password          : 'application-password',
					username          : 'application-user'
				],
				features   : [
					certManager: [active: true, issuer: 'production-issuer'],
					mail       : [
						active      : true,
						smtpAddress : 'smtp.example.org',
						smtpPassword: 'smtp-password',
						smtpPort    : 2525,
						smtpUser    : 'smtp-user'
					],
					monitoring : [
						grafanaEmailFrom: 'grafana@example.org',
						grafanaEmailTo  : 'team@example.org',
						grafanaUrl      : 'https://grafana.example.org',
						namespace       : 'observability',
						oidc            : [
							providerName  : 'Keycloak',
							issuerUrl     : '',
							clientId      : 'grafana-client',
							clientSecret  : '',
							scopes        : ['openid', 'profile', 'email'],
							adminGroupName: '',
							enabled       : false
						],
						helm            : [
							grafanaImage                  : 'grafana-image',
							grafanaSidecarImage           : 'sidecar-image',
							prometheusConfigReloaderImage : 'reloader-image',
							prometheusImage               : 'prometheus-image',
							prometheusOperatorImage       : 'operator-image'
						]
					]
				],
				jenkins    : [active: true],
				registry   : [createImagePullSecrets: true],
				scm        : [scmManager: [namespace: 'source-control'], scmProviderType: ScmProviderType.SCM_MANAGER]
			])
			.build())
	}

	private static DeploymentContext context(Config config) {
		return new DeploymentContext(
			config,
			DeploymentContext.TenantMode.MULTI_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.INTERNAL,
			true,
			DeploymentContext.ClusterDistribution.OPENSHIFT)
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
