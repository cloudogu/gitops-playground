package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.DeploymentContext;

/**
 * Installs tool-specific CRDs before the regular tool deployment starts.
 *
 * <p>Implementations run before repository workspaces and tool lifecycle state are prepared. They must therefore
 * derive everything they need from the deployment context and must not rely on {@link AbstractTool#execute} having
 * been called. Implementations must return only after the CRDs they install are established and ready for API
 * discovery.
 */
public interface CrdBootstrap {

	void bootstrapCrds(DeploymentContext context);
}
