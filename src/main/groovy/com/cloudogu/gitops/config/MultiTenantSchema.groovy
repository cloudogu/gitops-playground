package com.cloudogu.gitops.config

import com.cloudogu.gitops.features.git.config.ScmCentralSchema.GitlabCentralConfig
import com.cloudogu.gitops.features.git.config.ScmCentralSchema.ScmManagerCentralConfig
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_USEDEDICATED_DESCRIPTION

class MultiTenantSchema {

    @Option(
            names = ['--scm-central-provider'],
            description = "The SCM provider type. Possible values: SCM_MANAGER, GITLAB",
            defaultValue = "SCM_MANAGER"
    )
    ScmProviderType scmProviderType

    @JsonPropertyDescription("GitlabConfig")
    @Mixin
    GitlabCentralConfig gitlabConfig

    @JsonPropertyDescription("ScmmConfig")
    @Mixin
    ScmManagerCentralConfig scmmConfig

    @Option(names = ['--central-argocd-namespace'], description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralArgocdNamespace = 'argocd'

    @Option(names = ['--dedicated-instance'], description = CENTRAL_USEDEDICATED_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_USEDEDICATED_DESCRIPTION)
    Boolean useDedicatedInstance = false

}