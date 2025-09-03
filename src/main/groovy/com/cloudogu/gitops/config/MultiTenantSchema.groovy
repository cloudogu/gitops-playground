package com.cloudogu.gitops.config

import com.cloudogu.gitops.features.scm.config.ScmCentralSchema
import com.cloudogu.gitops.features.scm.config.util.ScmProviderType
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Option

import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_USEDEDICATED_DESCRIPTION

class MultiTenantSchema {

    ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER

    ScmCentralSchema.GitlabCentralConfig gitlabConfig

    ScmCentralSchema.ScmmCentralConfig scmmConfig

    @Option(names = ['--central-argocd-namespace'], description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralArgocdNamespace = 'argocd'

    @Option(names = ['--dedicated-instance'], description = CENTRAL_USEDEDICATED_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_USEDEDICATED_DESCRIPTION)
    Boolean useDedicatedInstance = false

}