package com.cloudogu.gitops.features.git.config

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Option

class ScmCentralSchema {

    static class GitlabCentralConfig implements GitlabConfig {

        public static final String CENTRAL_GITLAB_URL_DESCRIPTION = "URL for external Gitlab"
        public static final String CENTRAL_GITLAB_USERNAME_DESCRIPTION = "GitLab username for API access. Must be 'oauth2' when using Personal Access Token (PAT) authentication"
        public static final String CENTRAL_GITLAB_PASSWORD_DESCRIPTION = "Password for SCM Manager authentication"
        public static final String CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION = "Main Group for Gitlab where the GOP creates it's groups/repos"

        // Only supports external Gitlab for now
        @Option(names = ['--central-gitlab-url'], description = CENTRAL_GITLAB_URL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_URL_DESCRIPTION)
        String url = 'https://gitlab.com/'

        @Option(names = ['--central-gitlab-username'], description = CENTRAL_GITLAB_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_USERNAME_DESCRIPTION)
        String username = 'oauth2.0'

        @Option(names = ['--central-gitlab-token'], description = CENTRAL_GITLAB_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--central-gitlab-group-id'], description = CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION)
        String parentGroupId = ''

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

        String gitOpsUsername = ''
        String defaultVisibility = ''
    }

    static class ScmManagerCentralConfig implements ScmManagerConfig {

        public static final String CENTRAL_SCMM_INTERNAL_DESCRIPTION = 'SCM for Central Management is running on the same cluster, so k8s internal URLs can be used for access'
        public static final String CENTRAL_SCMM_URL_DESCRIPTION = 'URL for the centralized Management Repo'
        public static final String CENTRAL_SCMM_USERNAME_DESCRIPTION = 'CENTRAL SCMM username'
        public static final String CENTRAL_SCMM_PASSWORD_DESCRIPTION = 'CENTRAL SCMM password'
        public static final String CENTRAL_SCMM_PATH_DESCRIPTION = 'Root path for SCM Manager. In SCM-Manager it is always "repo"'
        public static final String CENTRAL_SCMM_NAMESPACE_DESCRIPTION = 'Namespace where to find the Central SCMM'

        @Option(names = ['--central-scmm-internal'], description = CENTRAL_SCMM_INTERNAL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_INTERNAL_DESCRIPTION)
        Boolean internal = false

        @Option(names = ['--central-scmm-url'], description = CENTRAL_SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--central-scmm-username'], description = CENTRAL_SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_USERNAME_DESCRIPTION)
        String username = ''

        @Option(names = ['--central-scmm-password'], description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--central-scmm-root-path'], description = CENTRAL_SCMM_PATH_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PATH_DESCRIPTION)
        String rootPath = 'repo'

        @Option(names = ['--central-scmm-namespace'], description = CENTRAL_SCMM_NAMESPACE_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_NAMESPACE_DESCRIPTION)
        String namespace = 'scm-manager'

        @Override
        String getIngress() {
            return null //Needed for setup
        }

        @Override
        Config.HelmConfigWithValues getHelm() {
            return null //Needed for setup
        }

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

        String gitOpsUsername = ''

    }
}