package com.cloudogu.gitops.application.context

import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema

import org.junit.jupiter.api.Test

class ContextBuilderTest {

	@Test
	void 'builds default deployment context from config'() {
		Config config = new Config()

		DeploymentContext context = new ContextBuilder(config).build()

		assertThat(context.config).isSameAs(config)
		assertThat(context.tenantMode).isEqualTo(DeploymentContext.TenantMode.SINGLE_TENANT)
		assertThat(context.isSingleTenant()).isTrue()
		assertThat(context.isMultiTenant()).isFalse()
		assertThat(context.scmManagerDeploymentMode).isEqualTo(DeploymentContext.DeploymentMode.EXTERNAL)
		assertThat(context.isInternalScmManager()).isFalse()
		assertThat(context.isExternalScmManager()).isTrue()
		assertThat(context.airgapped).isFalse()
		assertThat(context.isNonAirgapped()).isTrue()
		assertThat(context.clusterDistribution).isEqualTo(DeploymentContext.ClusterDistribution.KUBERNETES)
		assertThat(context.isOpenshift()).isFalse()
	}

	@Test
	void 'builds derived deployment context values from config'() {
		Config config = new Config()
		config.multiTenant.useDedicatedInstance = true
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig(internal: true)
		config.application.mirrorRepos = true
		config.application.openshift = true

		DeploymentContext context = new ContextBuilder(config).build()

		assertThat(context.tenantMode).isEqualTo(DeploymentContext.TenantMode.MULTI_TENANT)
		assertThat(context.isMultiTenant()).isTrue()
		assertThat(context.scmManagerDeploymentMode).isEqualTo(DeploymentContext.DeploymentMode.INTERNAL)
		assertThat(context.isInternalScmManager()).isTrue()
		assertThat(context.isExternalScmManager()).isFalse()
		assertThat(context.airgapped).isTrue()
		assertThat(context.clusterDistribution).isEqualTo(DeploymentContext.ClusterDistribution.OPENSHIFT)
		assertThat(context.isOpenshift()).isTrue()
	}
}
