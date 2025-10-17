package com.cloudogu.gitops.features.git.config

import com.cloudogu.gitops.config.Config
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

        @Option(names = ['--central-gitlab-url'], description = SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--central-gitlab-username'], description = SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = 'oauth2.0'

        @Option(names = ['--central-gitlab-token'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--central-gitlab-parent-id'], description = SCMM_PASSWORD_DESCRIPTION)
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

        @Option(names = ['--central-scmm-url'], description = CENTRAL_MGMT_REPO_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_MGMT_REPO_DESCRIPTION)
        String url = ''

        @Option(names = ['--central-scmm-username'], description = CENTRAL_SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_USERNAME_DESCRIPTION)
        String username = ''

        @Option(names = ['--central-scmm-password'], description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--central-scmm-path'], description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        String rootPath

        @Option(names = ['--central-scmm-namespace'], description = 'Namespace where the central scm resides in')
        @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
        String namespace = 'scm-manager'

        @Override
        String getIngress() {
            return null
        }

        @Override
        Config.HelmConfigWithValues getHelm() {
            return null
        }

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

        String gitOpsUsername = ''

    }
}