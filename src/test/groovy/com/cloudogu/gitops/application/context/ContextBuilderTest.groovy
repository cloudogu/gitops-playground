package com.cloudogu.gitops.application.context

import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import com.cloudogu.gitops.config.scm.util.ScmProviderType

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
		assertThat(context.scmManagerMode).isEqualTo(DeploymentContext.ScmManagerMode.EXTERNAL)
		assertThat(context.isExternalScmManager()).isTrue()
		assertThat(context.certManagerActive).isFalse()
		assertThat(context.airgapped).isFalse()
		assertThat(context.isNonAirgapped()).isTrue()
		assertThat(context.centralScmProviderType).isEqualTo(ScmProviderType.SCM_MANAGER)
		assertThat(context.tenantScmProviderType).isEqualTo(ScmProviderType.SCM_MANAGER)
		assertThat(context.platform).isEqualTo(DeploymentContext.Platform.KUBERNETES)
		assertThat(context.isOpenshift()).isFalse()
	}

	@Test
	void 'builds derived deployment context values from config'() {
		Config config = new Config()
		config.multiTenant.useDedicatedInstance = true
		config.multiTenant.scmProviderType = ScmProviderType.GITLAB
		config.scm.scmProviderType = ScmProviderType.GITLAB
		config.scm.scmManager = new ScmTenantSchema.ScmManagerTenantConfig(internal: true)
		config.features.certManager.active = true
		config.application.mirrorRepos = true
		config.application.openshift = true

		DeploymentContext context = new ContextBuilder(config).build()

		assertThat(context.tenantMode).isEqualTo(DeploymentContext.TenantMode.MULTI_TENANT)
		assertThat(context.isMultiTenant()).isTrue()
		assertThat(context.scmManagerMode).isEqualTo(DeploymentContext.ScmManagerMode.INTERNAL)
		assertThat(context.isInternalScmManager()).isTrue()
		assertThat(context.certManagerActive).isTrue()
		assertThat(context.airgapped).isTrue()
		assertThat(context.centralScmProviderType).isEqualTo(ScmProviderType.GITLAB)
		assertThat(context.tenantScmProviderType).isEqualTo(ScmProviderType.GITLAB)
		assertThat(context.platform).isEqualTo(DeploymentContext.Platform.OPENSHIFT)
		assertThat(context.isOpenshift()).isTrue()
	}
}