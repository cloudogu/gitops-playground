package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import lombok.Builder;

import java.util.Collection;
import java.util.List;
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
	Map<String, Object> rbacTemplateConfig
) {

	public ArgoCDToolConfig {
		activeNamespaces = activeNamespaces == null ? List.of() : List.copyOf(activeNamespaces);
		tenantNamespaces = tenantNamespaces == null ? List.of() : List.copyOf(tenantNamespaces);
		values = values == null ? Map.of() : Map.copyOf(values);
		templateConfig = templateConfig == null ? Map.of() : Map.copyOf(templateConfig);
		rbacTemplateConfig = rbacTemplateConfig == null ? Map.of() : Map.copyOf(rbacTemplateConfig);
	}
}
