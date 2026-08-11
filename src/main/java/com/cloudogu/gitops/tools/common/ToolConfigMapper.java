package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.application.context.DeploymentContext;

@FunctionalInterface
public interface ToolConfigMapper<T> {

	T map(DeploymentContext context);
}
