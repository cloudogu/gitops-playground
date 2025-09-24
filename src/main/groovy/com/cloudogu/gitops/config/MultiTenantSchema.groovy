package com.cloudogu.gitops.config

import com.cloudogu.gitops.features.git.config.ScmCentralSchema.GitlabCentralConfig
import com.cloudogu.gitops.features.git.config.ScmCentralSchema.ScmmCentralConfig
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_USEDEDICATED_DESCRIPTION

class MultiTenantSchema {

    ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER

    @JsonPropertyDescription("GitlabConfig")
    @Mixin
    GitlabCentralConfig gitlabConfig

    @JsonPropertyDescription("ScmmConfig")
    @Mixin
    ScmmCentralConfig scmmConfig

    @Option(names = ['--central-argocd-namespace'], description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralArgocdNamespace = 'argocd'

    @Option(names = ['--dedicated-instance'], description = CENTRAL_USEDEDICATED_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_USEDEDICATED_DESCRIPTION)
    Boolean useDedicatedInstance = false

    @JsonIgnore
    //internal centralized is not supported by now
    Boolean isInternal() {
            return false
    }

    @Option(names = ['--central-scm-namespace'], description = 'Namespace where the central scm resides in')
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralSCMamespace = 'scm-manager'
}