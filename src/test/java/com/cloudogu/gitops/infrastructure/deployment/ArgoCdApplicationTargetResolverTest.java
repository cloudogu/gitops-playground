package com.cloudogu.gitops.infrastructure.deployment;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.MultiTenantSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArgoCdApplicationTargetResolverTest {

	@Test
	void resolvesTargetForSingleTenantDeployment() {
		Config config = createConfig();

		ArgoCdApplicationTarget target = new ArgoCdApplicationTargetResolver(config)
			.resolve(new ContextBuilder(config).build(), "repo-name");

		assertThat(target.getApplicationName()).isEqualTo("foo-repo-name");
		assertThat(target.getNamespace()).isEqualTo("foo-argocd");
		assertThat(target.getProject()).isEqualTo("cluster-resources");
		assertThat(target.isCreateDestinationNamespace()).isTrue();
	}

	@Test
	void resolvesTargetForMultiTenantDeployment() {
		Config config = createConfig();
		config.getMultiTenant().setUseDedicatedInstance(true);
		config.getMultiTenant().setCentralArgocdNamespace("central-argocd");

		ArgoCdApplicationTarget target = new ArgoCdApplicationTargetResolver(config)
			.resolve(new ContextBuilder(config).build(), "repo-name");

		assertThat(target.getApplicationName()).isEqualTo("foo-repo-name");
		assertThat(target.getNamespace()).isEqualTo("central-argocd");
		assertThat(target.getProject()).isEqualTo("foo");
		assertThat(target.isCreateDestinationNamespace()).isTrue();
	}

	@Test
	void disablesDestinationNamespaceCreationInOperatorMode() {
		Config config = createConfig();
		config.getFeatures().getArgocd().setOperator(true);

		ArgoCdApplicationTarget target = new ArgoCdApplicationTargetResolver(config)
			.resolve(new ContextBuilder(config).build(), "repo-name");

		assertThat(target.isCreateDestinationNamespace()).isFalse();
	}

	private static Config createConfig() {
		Config config = new Config();

		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setNamePrefix("foo-");
		config.setApplication(application);

		Config.ArgoCDSchema argoCd = new Config.ArgoCDSchema();
		argoCd.setNamespace("argocd");
		Config.FeaturesSchema features = new Config.FeaturesSchema();
		features.setArgocd(argoCd);
		config.setFeatures(features);

		config.setMultiTenant(new MultiTenantSchema());
		return config;
	}
}
