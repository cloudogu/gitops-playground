package com.cloudogu.gitops.features.git.config

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Option
import static com.cloudogu.gitops.config.ConfigConstants.*

class ScmCentralSchema {

    static class GitlabCentralConfig implements GitlabConfig {
        // Only supports external Gitlab for now
        Boolean internal = false

        @Option(names = ['--gitlab-central-url'], description = SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--gitlab-central-username'], description = SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = 'oauth2.0'

        @Option(names = ['--gitlab-central-token'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--gitlab-central-parent-id'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String parentGroupId = ''

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

        String gitOpsUsername = ''

    }

    static class ScmManagerCentralConfig implements ScmManagerConfig {

        @Option(names = ['--dedicated-internal'], description = CENTRAL_SCM_INTERNAL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCM_INTERNAL_DESCRIPTION)
        Boolean internal = false

        @Option(names = ['--central-scm-url'], description = CENTRAL_MGMT_REPO_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_MGMT_REPO_DESCRIPTION)
        String url = ''

        @Option(names = ['--central-scm-username'], description = CENTRAL_SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_USERNAME_DESCRIPTION)
        String username = ''

        @Option(names = ['--central-scm-password'], description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--central-scm-path'], description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        String rootPath

        @Option(names = ['--central-scm-namespace'], description = 'Namespace where the central scm resides in')
        @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
        String namespace = 'scm-manager'

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

        String gitOpsUsername = ''

    }
}