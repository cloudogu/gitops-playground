package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryToolConfigMapperTest {

	@Test
	void mapsAllRelevantValuesFromDeploymentContextAndConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("test-");
		config.getApplication().setLocalHelmChartFolder("/charts");
		config.getApplication().setNetpols(true);
		config.getApplication().getNetworkPolicies().setRegistryAccessCidrs(List.of("192.168.10.0/24"));
		config.getRegistry().setActive(true);
		config.getRegistry().setInternal(true);
		config.getRegistry().setNamespace("images");
		config.getRegistry().setInternalPort(32000);
		config.getRegistry().getHelm().setRepoURL("https://registry.example.org");
		config.getRegistry().getHelm().setChart("registry-chart");
		config.getRegistry().getHelm().setVersion("4.5.6");
		config.getRegistry().getHelm().setValues(Map.of("storage", "memory"));

		RegistryToolConfig actual = new RegistryToolConfigMapper(config).map(context());

		assertThat(actual).isEqualTo(RegistryToolConfig.builder()
													   .active(true)
													   .internal(true)
													   .namespace("test-images")
													   .bootstrapNodePort(Config.DEFAULT_REGISTRY_PORT)
													   .internalPort(32000)
													   .netpols(true)
													   .registryAccessCidrs(List.of("192.168.10.0/24"))
													   .helm(HelmChartConfig.builder()
																			.repoURL("https://registry.example.org")
																			.chart("registry-chart")
																			.version("4.5.6")
																			.values(Map.of("storage", "memory"))
																			.localHelmChartFolder("/charts")
																			.build())
													   .build());
	}

	@Test
	void doesNotExposeANamespaceForAnExternalRegistry() {
		Config config = new Config();
		config.getRegistry().setInternal(false);

		RegistryToolConfig actual = new RegistryToolConfigMapper(config).map(context());

		assertThat(actual.namespace()).isNull();
	}

	private static DeploymentContext context() {
		return new DeploymentContext(
			DeploymentContext.TenantMode.SINGLE_TENANT,
			DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
			DeploymentContext.ArgoCdDeploymentMode.DISABLED,
			false,
			DeploymentContext.ClusterDistribution.KUBERNETES
		);
	}
}
