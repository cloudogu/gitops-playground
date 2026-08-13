package com.cloudogu.gitops.config;

import com.cloudogu.gitops.config.scm.ScmCentralSchema.GitlabCentralConfig;
import com.cloudogu.gitops.config.scm.ScmCentralSchema.ScmManagerCentralConfig;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Getter
@Setter
@NoArgsConstructor
public class MultiTenantSchema {

	public static final String SCM_PROVIDER_TYPE_DESCRIPTION = "The SCM provider type. Possible values: SCM_MANAGER, GITLAB";
	public static final String GITLAB_CONFIG_DESCRIPTION = "Config for GITLAB";
	public static final String SCMM_CONFIG_DESCRIPTION = "Config for SCM-Manager";
	public static final String CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION = "Namespace for the centralized Argocd";
	public static final String CENTRAL_USEDEDICATED_DESCRIPTION = "Toggles the Dedicated Instances Mode. See docs for more info";

	@Option(names = {"--central-scm-provider"}, description = SCM_PROVIDER_TYPE_DESCRIPTION, defaultValue = "SCM_MANAGER")
	@JsonPropertyDescription(SCM_PROVIDER_TYPE_DESCRIPTION)
	private ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER;

	@JsonPropertyDescription(GITLAB_CONFIG_DESCRIPTION)
	@Mixin
	private GitlabCentralConfig gitlab;

	@JsonPropertyDescription(SCMM_CONFIG_DESCRIPTION)
	@Mixin
	private ScmManagerCentralConfig scmManager;

	@Option(names = {"--central-argocd-namespace"}, description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
	@JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
	private String centralArgocdNamespace = "argocd";

	@Option(names = {"--dedicated-instance"}, description = CENTRAL_USEDEDICATED_DESCRIPTION)
	@JsonPropertyDescription(CENTRAL_USEDEDICATED_DESCRIPTION)
	private Boolean useDedicatedInstance = false;
}
