package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.config.scm.util.ScmProviderType
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ArgoCDToolConfigMapperTest {

	@Test
	void 'maps all relevant values from deployment context and config'() {
		Config config = new Config()
		config.application.namePrefix = 'tenant-a-'
		config.application.password = 'application-password'
		config.application.namespaces.dedicatedNamespaces = ['argocd', 'monitoring'] as LinkedHashSet
		config.application.namespaces.tenantNamespaces = ['team-a', 'team-b'] as LinkedHashSet
		config.application.netpols = true
		config.application.clusterAdmin = true
		config.application.insecure = true
		// Intentionally differs from the DeploymentContext to verify derived values come from the context.
		config.application.mirrorRepos = false
		config.application.openshift = false
		config.application.skipCrds = true
		config.features.argocd.active = true
		config.features.argocd.namespace = 'gitops'
		config.features.argocd.operator = true
		config.features.argocd.url = 'https://argocd.example.org'
		config.features.argocd.emailFrom = 'argocd@example.org'
		config.features.argocd.emailToAdmin = 'admins@example.org'
		config.features.argocd.env = [[name: 'FIRST', value: 'one']]
		config.features.argocd.resourceInclusionsCluster = 'https://cluster.example.org'
		config.features.argocd.values = [server: [replicas: 2]]
		config.features.argocd.oidc.clientId = 'argocd-client'
		config.features.certManager.active = true
		config.features.certManager.issuer = 'production-issuer'
		config.features.mail.active = true
		config.features.mail.smtpAddress = 'smtp.example.org'
		config.features.mail.smtpPort = 2525
		config.features.mail.smtpUser = 'smtp-user'
		config.features.mail.smtpPassword = 'smtp-password'
		config.features.monitoring.active = true
		config.features.monitoring.namespace = 'observability'
		config.features.secrets.active = true
		config.multiTenant.centralArgocdNamespace = 'central-gitops'
		config.scm.scmProviderType = ScmProviderType.SCM_MANAGER
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig(namespace: 'source-control')
		Config.ContentSchema.HelmReleaseSchema helmRelease = new Config.ContentSchema.HelmReleaseSchema()
		helmRelease.name = 'database'
		helmRelease.chart = 'postgresql'
		helmRelease.repoURL = 'https://charts.example.org'
		config.content.helmReleases = [helmRelease]

		ArgoCDToolConfig actual = new ArgoCDToolConfigMapper(config).map(context(config))

		assertThat(actual).isEqualTo(ArgoCDToolConfig.builder()
			.active(true)
			.namespace('tenant-a-gitops')
			.password('application-password')
			.operator(true)
			.activeNamespaces(['argocd', 'monitoring', 'team-a', 'team-b'])
			.smtpUser('smtp-user')
			.smtpPassword('smtp-password')
			.values([server: [replicas: 2]])
			.multiTenant(true)
			.netpols(true)
			.tenantName('tenant-a')
			.url('https://argocd.example.org')
			.tenantNamespaces(['team-a', 'team-b'])
			.centralNamespace('central-gitops')
			.clusterAdmin(true)
			.scmProviderType(ScmProviderType.SCM_MANAGER)
			.templateConfig([
				application: [
					clusterAdmin: true,
					insecure    : true,
					mirrorRepos : true,
					namePrefix  : 'tenant-a-',
					netpols     : true,
					openshift   : true,
					skipCrds    : true
				],
				content    : [helmReleases: [[repoURL: 'https://charts.example.org']]],
				features   : [
					argocd     : [
						emailFrom                 : 'argocd@example.org',
						emailToAdmin              : 'admins@example.org',
						env                       : [[name: 'FIRST', value: 'one']],
						namespace                 : 'gitops',
						oidc                      : [
							providerName  : 'Keycloak',
							issuerUrl     : '',
							clientId      : 'argocd-client',
							clientSecret  : '',
							scopes        : ['openid', 'profile', 'email'],
							adminGroupName: '',
							enabled       : false
						],
						operator                  : true,
						resourceInclusionsCluster : 'https://cluster.example.org',
						url                       : 'https://argocd.example.org'
					],
					certManager: [active: true, issuer: 'production-issuer'],
					mail       : [
						active      : true,
						smtpAddress : 'smtp.example.org',
						smtpPassword: 'smtp-password',
						smtpPort    : 2525,
						smtpUser    : 'smtp-user'
					],
					monitoring : [active: true, namespace: 'observability'],
					secrets    : [active: true]
				],
				multiTenant: [centralArgocdNamespace: 'central-gitops'],
				scm        : [scmManager: [namespace: 'source-control'], scmProviderType: ScmProviderType.SCM_MANAGER]
			])
			.rbacTemplateConfig([
				application: [openshift: true],
				features   : [monitoring: [active: true], secrets: [active: true]]
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
}
