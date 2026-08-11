package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;

public abstract class AbstractMappedTool<T> extends AbstractTool {

	private final ToolConfigMapper<T> toolConfigMapper;
	private T toolConfig;

	protected AbstractMappedTool(ToolConfigMapper<T> toolConfigMapper) {
		this.toolConfigMapper = toolConfigMapper;
	}

	@Override
	public final boolean isEnabled(DeploymentContext context) {
		return isEnabled(toolConfigMapper.map(context));
	}

	protected abstract boolean isEnabled(T config);

	@Override
	protected void prepareExecution(DeploymentContext context, RepositoryWorkspace workspace) {
		super.prepareExecution(context, workspace);
		this.toolConfig = toolConfigMapper.map(context);
	}

	protected String activeNamespace(T config) {
		return null;
	}

	@Override
	public final String getActiveNamespaceFromFeature(DeploymentContext context) {
		T mappedConfig = toolConfigMapper.map(context);
		return isEnabled(mappedConfig) ? activeNamespace(mappedConfig) : null;
	}

	protected final T toolConfig() {
		return toolConfig;
	}
}
