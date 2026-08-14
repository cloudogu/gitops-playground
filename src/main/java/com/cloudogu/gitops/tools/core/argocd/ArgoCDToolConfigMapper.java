package com.cloudogu.gitops.tools.core.argocd;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Singleton
@RequiredArgsConstructor
public class ArgoCDToolConfigMapper implements ToolConfigMapper<ArgoCDToolConfig> {

	private final Config config;

	@Override
	public ArgoCDToolConfig map(DeploymentContext context) {
		Config.ArgoCDSchema argocd = config.getFeatures().getArgocd();
		Collection<String> activeNamespaces = config.getApplication().getNamespaces().getActiveNamespaces();
		Collection<String> tenantNamespaces = config.getApplication().getNamespaces().getTenantNamespaces();
		return ArgoCDToolConfig.builder()
							   .active(argocd.getActive())
							   .namespace(config.getApplication().getNamePrefix() + argocd.getNamespace())
							   .password(config.getApplication().getPassword())
							   .operator(argocd.getOperator())
							   .activeNamespaces(activeNamespaces)
							   .smtpUser(config.getFeatures().getMail().getSmtpUser())
							   .smtpPassword(config.getFeatures().getMail().getSmtpPassword())
							   .values(argocd.getValues())
							   .multiTenant(context.isMultiTenant())
							   .netpols(config.getApplication().getNetpols())
							   .tenantName(config.getApplication().getTenantName())
							   .url(argocd.getUrl())
							   .tenantNamespaces(tenantNamespaces)
							   .centralNamespace(config.getMultiTenant().getCentralArgocdNamespace())
							   .clusterAdmin(config.getApplication().getClusterAdmin())
							   .scmProviderType(config.getScm().getScmProviderType())
							   .templateConfig(templateConfig(config, context))
							   .rbacTemplateConfig(rbacTemplateConfig(config, context))
							   .build();
	}

	private static Map<String, Object> rbacTemplateConfig(Config config, DeploymentContext context) {
		return new TemplateConfig()
			.put("application.openshift", context.isOpenshift())
			.put("features.monitoring.active", config.getFeatures().getMonitoring().getActive())
			.put("features.secrets.active", config.getFeatures().getSecrets().getActive())
			.values();
	}

	private static Map<String, Object> templateConfig(Config config, DeploymentContext context) {
		String scmManagerNamespace = config.getScm() == null || config.getScm().getScmManager() == null
			? "scm-manager"
			: config.getScm().getScmManager().getNamespace();
		return new TemplateConfig()
			.put("application.clusterAdmin", config.getApplication().getClusterAdmin())
			.put("application.insecure", config.getApplication().getInsecure())
			.put("application.mirrorRepos", context.isAirgapped())
			.put("application.namePrefix", config.getApplication().getNamePrefix())
			.put("application.netpols", config.getApplication().getNetpols())
			.put("application.openshift", context.isOpenshift())
			.put("application.skipCrds", config.getApplication().getSkipCrds())
			.put(
				"content.helmReleases",
				config.getContent() == null
					? List.of()
					: ToolConfigMapperSupport.helmReleaseRepositories(config.getContent().getHelmReleases())
			)
			.put("features.argocd.emailFrom", config.getFeatures().getArgocd().getEmailFrom())
			.put("features.argocd.emailToAdmin", config.getFeatures().getArgocd().getEmailToAdmin())
			.put("features.argocd.env", config.getFeatures().getArgocd().getEnv())
			.put("features.argocd.namespace", config.getFeatures().getArgocd().getNamespace())
			.put("features.argocd.oidc", ToolConfigMapperSupport.oidc(config.getFeatures().getArgocd().getOidc()))
			.put("features.argocd.operator", config.getFeatures().getArgocd().getOperator())
			.put(
				"features.argocd.resourceInclusionsCluster",
				config.getFeatures().getArgocd().getResourceInclusionsCluster()
			)
			.put("features.argocd.url", config.getFeatures().getArgocd().getUrl())
			.put("features.certManager.active", config.getFeatures().getCertManager().getActive())
			.put("features.certManager.issuer", config.getFeatures().getCertManager().getIssuer())
			.put("features.mail.active", config.getFeatures().getMail().getActive())
			.put("features.mail.smtpAddress", config.getFeatures().getMail().getSmtpAddress())
			.put("features.mail.smtpPassword", config.getFeatures().getMail().getSmtpPassword())
			.put("features.mail.smtpPort", config.getFeatures().getMail().getSmtpPort())
			.put("features.mail.smtpUser", config.getFeatures().getMail().getSmtpUser())
			.put("features.monitoring.active", config.getFeatures().getMonitoring().getActive())
			.put("features.monitoring.namespace", config.getFeatures().getMonitoring().getNamespace())
			.put("features.secrets.active", config.getFeatures().getSecrets().getActive())
			.put("multiTenant.centralArgocdNamespace", config.getMultiTenant().getCentralArgocdNamespace())
			.put("scm.scmManager.namespace", scmManagerNamespace)
			.put("scm.scmProviderType", config.getScm().getScmProviderType())
			.values();
	}
}
