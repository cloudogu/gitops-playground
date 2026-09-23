package com.cloudogu.gitops.tools.core.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.tools.common.TemplateConfig;
import com.cloudogu.gitops.tools.common.ToolConfigMapper;
import com.cloudogu.gitops.tools.common.ToolConfigMapperSupport;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Singleton
@RequiredArgsConstructor
public class ScmManagerToolConfigMapper implements ToolConfigMapper<ScmManagerToolConfig> {

	private final Config config;

	@Override
	public ScmManagerToolConfig map(DeploymentContext context) {
		ScmTenantSchema.ScmManagerTenantConfig scmManager = config.getScm() == null
			|| config.getScm().getScmManager() == null
			? new ScmTenantSchema.ScmManagerTenantConfig()
			: config.getScm().getScmManager();
		String namePrefix = config.getApplication().getNamePrefix() == null ? "" : config.getApplication().getNamePrefix();
		String baseNamespace = scmManager.getNamespace() == null ? "scm-manager" : scmManager.getNamespace();
		String namespace = !namePrefix.isEmpty() && baseNamespace.startsWith(namePrefix)
			? baseNamespace
			: namePrefix + baseNamespace;
		String releaseName = namePrefix.strip().isEmpty() ? "scmm" : namePrefix.strip() + "scmm";

		return ScmManagerToolConfig.builder()
								   .active(context.isInternalScmManager())
								   .multiTenant(context.isMultiTenant())
								   .namePrefix(namePrefix)
								   .namespace(namespace)
								   .releaseName(releaseName)
								   .ingress(scmManager.getIngress())
								   .gitOpsUsername(scmManager.getGitOpsUsername())
								   .skipPlugins(scmManager.getSkipPlugins())
								   .skipRestart(scmManager.getSkipRestart())
								   .netpols(config.getApplication().getNetpols())
								   .bootstrapCidrs(ToolConfigMapperSupport.networkPolicyBootstrapCidrs(config))
								   .argocdActive(context.isArgoCdEnabled())
								   .argocdNamespace(namePrefix + config.getFeatures().getArgocd().getNamespace())
								   .ingressActive(config.getFeatures().getIngress().getActive())
								   .ingressNamespace(namePrefix + config.getFeatures().getIngress().getIngressNamespace())
								   .monitoringActive(config.getFeatures().getMonitoring().getActive())
								   .monitoringNamespace(namePrefix + config.getFeatures().getMonitoring().getNamespace())
								   .jenkinsActive(config.getJenkins().getActive())
								   .jenkinsInternal(config.getJenkins().getInternal())
								   .jenkinsNamespace(namePrefix + config.getJenkins().getNamespace())
								   .jenkinsUrl(config.getJenkins().getUrlForScm())
								   .helm(ToolConfigMapperSupport.helmChart(
									   scmManager.getHelm(),
									   config.getApplication().getLocalHelmChartFolder()
								   ))
								   .imagePullSecret(ToolConfigMapperSupport.imagePullSecret(config.getRegistry()))
								   .templateConfig(templateConfig(config, scmManager))
								   .build();
	}

	private static Map<String, Object> templateConfig(
		Config config,
		ScmTenantSchema.ScmManagerTenantConfig scmManager) {
		return new TemplateConfig()
			.put("features.certManager.active", config.getFeatures().getCertManager().getActive())
			.put("features.certManager.issuer", config.getFeatures().getCertManager().getIssuer())
			.put("registry.createImagePullSecrets", config.getRegistry().getCreateImagePullSecrets())
			.put("scm.scmManager.scmmImage", scmManager.getScmmImage())
			.values();
	}
}
