package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.tools.common.ImmutableConfigData;
import lombok.Builder;

import java.util.Collection;
import java.util.Map;

@Builder
public record ArgoCDToolConfig(
	boolean active,
	String namespace,
	String password,
	boolean operator,
	Collection<String> activeNamespaces,
	String smtpUser,
	String smtpPassword,
	Map<String, Object> values,
	boolean multiTenant,
	boolean netpols,
	String tenantName,
	String url,
	Collection<String> tenantNamespaces,
	String centralNamespace,
	boolean clusterAdmin,
	ScmProviderType scmProviderType,
	Map<String, Object> templateConfig,
	Map<String, Object> rbacTemplateConfig) {

	public ArgoCDToolConfig {
		activeNamespaces = ImmutableConfigData.copyList(activeNamespaces);
		tenantNamespaces = ImmutableConfigData.copyList(tenantNamespaces);
		values = ImmutableConfigData.copyMap(values);
		templateConfig = ImmutableConfigData.copyMap(templateConfig);
		rbacTemplateConfig = ImmutableConfigData.copyMap(rbacTemplateConfig);
	}
}
