package com.cloudogu.gitops.config;

import com.cloudogu.gitops.config.scm.ScmCentralSchema.GitlabCentralConfig;
import com.cloudogu.gitops.config.scm.ScmCentralSchema.ScmManagerCentralConfig;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

public class MultiTenantSchema {

    public static final String SCM_PROVIDER_TYPE_DESCRIPTION = "The SCM provider type. Possible values: SCM_MANAGER, GITLAB";
    public static final String GITLAB_CONFIG_DESCRIPTION = "Config for GITLAB";
    public static final String SCMM_CONFIG_DESCRIPTION = "Config for GITLAB";
    public static final String CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION = "Namespace for the centralized Argocd";
    public static final String CENTRAL_USEDEDICATED_DESCRIPTION = "Toggles the Dedicated Instances Mode. See docs for more info";

    @Option(names = {"--central-scm-provider"},
            description = SCM_PROVIDER_TYPE_DESCRIPTION,
            defaultValue = "SCM_MANAGER")
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

    public ScmProviderType getScmProviderType() {
        return scmProviderType;
    }

    public void setScmProviderType(ScmProviderType scmProviderType) {
        this.scmProviderType = scmProviderType;
    }

    public GitlabCentralConfig getGitlab() {
        return gitlab;
    }

    public void setGitlab(GitlabCentralConfig gitlab) {
        this.gitlab = gitlab;
    }

    public ScmManagerCentralConfig getScmManager() {
        return scmManager;
    }

    public void setScmManager(ScmManagerCentralConfig scmManager) {
        this.scmManager = scmManager;
    }

    public String getCentralArgocdNamespace() {
        return centralArgocdNamespace;
    }

    public void setCentralArgocdNamespace(String centralArgocdNamespace) {
        this.centralArgocdNamespace = centralArgocdNamespace;
    }

    public Boolean getUseDedicatedInstance() {
        return useDedicatedInstance;
    }

    public void setUseDedicatedInstance(Boolean useDedicatedInstance) {
        this.useDedicatedInstance = useDedicatedInstance;
    }
}
