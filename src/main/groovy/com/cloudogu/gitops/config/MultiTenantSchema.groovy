import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Option


import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_MGMT_REPO_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_SCMM_PASSWORD_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_SCMM_USERNAME_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_SCM_INTERNAL_DESCRIPTION
import static com.cloudogu.gitops.config.ConfigConstants.CENTRAL_USEDEDICATED_DESCRIPTION

class MultiTenantSchema {

    @Option(names = ['--dedicated-internal'], description = CENTRAL_SCM_INTERNAL_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_SCM_INTERNAL_DESCRIPTION)
    Boolean internal = false

    @Option(names = ['--dedicated-instance'], description = CENTRAL_USEDEDICATED_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_USEDEDICATED_DESCRIPTION)
    Boolean useDedicatedInstance = false

    @Option(names = ['--central-scm-url'], description = CENTRAL_MGMT_REPO_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_MGMT_REPO_DESCRIPTION)
    String centralScmUrl = ''

    @Option(names = ['--central-scm-username'], description = CENTRAL_SCMM_USERNAME_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_SCMM_USERNAME_DESCRIPTION)
    String username = ''

    @Option(names = ['--central-scm-password'], description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
    String password = ''

    @Option(names = ['--central-argocd-namespace'], description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralArgocdNamespace = 'argocd'

    @Option(names = ['--central-scm-namespace'], description = CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
    String centralSCMamespace = 'scm-manager'


}