package com.cloudogu.gitops.tools;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;

@Singleton
public class RegistryToolConfigMapper implements ToolConfigMapper<RegistryToolConfig> {

	@Override
	public RegistryToolConfig map(DeploymentContext context) {
		Config config = context.getConfig();
		Config.RegistrySchema registry = config.getRegistry();
		String namespace = registry.getInternal()
			? config.getApplication().getNamePrefix() + registry.getNamespace()
			: null;

		return RegistryToolConfig.builder()
								 .active(registry.getActive())
								 .internal(registry.getInternal())
								 .namespace(namespace)
								 .internalPort(registry.getInternalPort())
								 .helm(ToolConfigMapperSupport.helmChart(
			                         registry.getHelm(),
			                         config.getApplication().getLocalHelmChartFolder()
		                         ))
								 .build();
	}
}
