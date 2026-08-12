package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;

import java.util.Objects;

/**
 * Base class for tools that consume a focused, tool-specific configuration instead of the complete GOP config.
 *
 * <p>The mapper is invoked during technical execution preparation, before the lifecycle starts. Lifecycle methods
 * access only the mapped configuration through {@link #toolConfig()}.
 *
 * @param <T> immutable configuration view required by the concrete tool
 */
public abstract class AbstractMappedTool<T> extends AbstractTool {

	private final ToolConfigMapper<T> toolConfigMapper;
	private T toolConfig;

	protected AbstractMappedTool(ToolConfigMapper<T> toolConfigMapper) {
		this.toolConfigMapper = Objects.requireNonNull(toolConfigMapper, "Tool config mapper must not be null");
	}

	@Override
	public final boolean isEnabled(DeploymentContext context) {
		return isEnabled(mapConfig(context));
	}

	protected abstract boolean isEnabled(T config);

	@Override
	protected void prepareExecution(DeploymentContext context, RepositoryWorkspace workspace) {
		this.toolConfig = null;
		super.prepareExecution(context, workspace);
		this.toolConfig = mapConfig(context);
	}

	protected String activeNamespace(T config) {
		return null;
	}

	@Override
	public final String getActiveNamespaceFromFeature(DeploymentContext context) {
		T mappedConfig = mapConfig(context);
		return isEnabled(mappedConfig) ? activeNamespace(mappedConfig) : null;
	}

	private T mapConfig(DeploymentContext context) {
		Objects.requireNonNull(context, "Deployment context must not be null");
		return Objects.requireNonNull(
			toolConfigMapper.map(context),
			() -> "Tool config mapper returned null for " + getClass().getName()
		);
	}

	protected final T toolConfig() {
		return Objects.requireNonNull(toolConfig, "Tool config is only available during and after execution preparation");
	}
}
