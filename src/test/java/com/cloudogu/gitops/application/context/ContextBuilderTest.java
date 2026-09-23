package com.cloudogu.gitops.application.context;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderTest {

	@Test
	void buildsDefaultDeploymentContextFromConfig() {
		Config config = new Config();

		DeploymentContext context = new ContextBuilder(config).build();

		assertThat(context.getTenantMode()).isEqualTo(DeploymentContext.TenantMode.SINGLE_TENANT);
		assertThat(context.isSingleTenant()).isTrue();
		assertThat(context.isMultiTenant()).isFalse();
		assertThat(context.getScmManagerDeploymentMode()).isEqualTo(DeploymentContext.ScmManagerDeploymentMode.EXTERNAL);
		assertThat(context.isInternalScmManager()).isFalse();
		assertThat(context.isExternalScmManager()).isTrue();
		assertThat(context.getArgoCdDeploymentMode()).isEqualTo(DeploymentContext.ArgoCdDeploymentMode.DISABLED);
		assertThat(context.isArgoCdEnabled()).isFalse();
		assertThat(context.isArgoCdOperator()).isFalse();
		assertThat(context.isArgoCdHelm()).isFalse();
		assertThat(context.isAirgapped()).isFalse();
		assertThat(context.getClusterDistribution()).isEqualTo(DeploymentContext.ClusterDistribution.KUBERNETES);
		assertThat(context.isOpenshift()).isFalse();
	}

	@Test
	void buildsDerivedDeploymentContextValuesFromConfig() {
		Config config = new Config();
		config.getMultiTenant().setUseDedicatedInstance(true);
		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setInternal(true);
		config.getScm().setScmManager(scmManager);
		config.getApplication().setMirrorRepos(true);
		config.getApplication().setOpenshift(true);
		config.getFeatures().getArgocd().setActive(true);
		config.getFeatures().getArgocd().setOperator(true);

		DeploymentContext context = new ContextBuilder(config).build();

		assertThat(context.getTenantMode()).isEqualTo(DeploymentContext.TenantMode.MULTI_TENANT);
		assertThat(context.isMultiTenant()).isTrue();
		assertThat(context.getScmManagerDeploymentMode()).isEqualTo(DeploymentContext.ScmManagerDeploymentMode.INTERNAL);
		assertThat(context.isInternalScmManager()).isTrue();
		assertThat(context.isExternalScmManager()).isFalse();
		assertThat(context.getArgoCdDeploymentMode()).isEqualTo(DeploymentContext.ArgoCdDeploymentMode.OPERATOR);
		assertThat(context.isArgoCdEnabled()).isTrue();
		assertThat(context.isArgoCdOperator()).isTrue();
		assertThat(context.isArgoCdHelm()).isFalse();
		assertThat(context.isAirgapped()).isTrue();
		assertThat(context.getClusterDistribution()).isEqualTo(DeploymentContext.ClusterDistribution.OPENSHIFT);
		assertThat(context.isOpenshift()).isTrue();
	}

	@Test
	void buildsHelmArgoCdDeploymentModeFromConfig() {
		Config config = new Config();
		config.getFeatures().getArgocd().setActive(true);

		DeploymentContext context = new ContextBuilder(config).build();

		assertThat(context.getArgoCdDeploymentMode()).isEqualTo(DeploymentContext.ArgoCdDeploymentMode.HELM);
		assertThat(context.isArgoCdEnabled()).isTrue();
		assertThat(context.isArgoCdOperator()).isFalse();
		assertThat(context.isArgoCdHelm()).isTrue();
	}

	@Test
	void keepsArgoCdDisabledWhenOnlyOperatorFlagIsSet() {
		Config config = new Config();
		config.getFeatures().getArgocd().setOperator(true);

		DeploymentContext context = new ContextBuilder(config).build();

		assertThat(context.getArgoCdDeploymentMode()).isEqualTo(DeploymentContext.ArgoCdDeploymentMode.DISABLED);
		assertThat(context.isArgoCdEnabled()).isFalse();
		assertThat(context.isArgoCdOperator()).isFalse();
		assertThat(context.isArgoCdHelm()).isFalse();
	}
}
