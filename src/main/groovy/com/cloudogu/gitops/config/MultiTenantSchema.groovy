package com.cloudogu.gitops.config

import com.cloudogu.gitops.features.git.config.ScmCentralSchema.GitlabCentralConfig
import com.cloudogu.gitops.features.git.config.ScmCentralSchema.ScmManagerCentralConfig
import com.cloudogu.gitops.features.git.config.util.ScmProviderType

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

class MultiTenantSchema {

    static final String SCM_PROVIDER_TYPE_DESCRIPTION = 'The SCM provider type. Possible values: SCM_MANAGER, GITLAB'
    static final String GITLAB_CONFIG_DESCRIPTION = 'Config for GITLAB'
    static final String SCMM_CONFIG_DESCRIPTION = 'Config for GITLAB'
    static final String CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION = 'Namespace for the centralized Argocd'
    static final String CENTRAL_USEDEDICATED_DESCRIPTION = 'Toggles the Dedicated Instances Mode. See docs for more info'

    @Option(
            names = ['--central-scm-provider'],
            description = SCM_PROVIDER_TYPE_DESCRIPTION,
            defaultValue = "SCM_MANAGER"
    )
    @JsonPropertyDescription(SCM_PROVIDER_TYPE_DESCRIPTION)
    ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER

    @JsonPropertyDescription(GITLAB_CONFIG_DESCRIPTION)
    @Mixin
    GitlabCentralConfig gitlab

    @JsonPropertyDescription(SCMM_CONFIG_DESCRIPTION)
    @Mixin
    ScmManagerCentralConfig scmManager

    @Option(names = ['--central-argocd-namespace'], description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralArgocdNamespace = 'argocd'

    @Option(names = ['--dedicated-instance'], description = CENTRAL_USEDEDICATED_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_USEDEDICATED_DESCRIPTION)
    Boolean useDedicatedInstance = false

}
