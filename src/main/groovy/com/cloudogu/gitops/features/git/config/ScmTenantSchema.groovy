package com.cloudogu.gitops.features.git.config

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.utils.NetworkingUtils
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonMerge
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_DESCRIPTION

class ScmTenantSchema {

    static final String GITLAB_CONFIG_DESCRIPTION = 'Config for GITLAB'
    static final String SCMM_CONFIG_DESCRIPTION = 'Config for GITLAB'
    static final String SCM_PROVIDER_TYPE_DESCRIPTION = 'The SCM provider type. Possible values: SCM_MANAGER, GITLAB'
    static final String GITOPSUSERNAME_DESCRIPTION = 'Username for the Gitops User'

    @Option(
            names = ['--scm-provider'],
            description = SCM_PROVIDER_TYPE_DESCRIPTION,
            defaultValue = "SCM_MANAGER"
    )
    @JsonPropertyDescription(SCM_PROVIDER_TYPE_DESCRIPTION)
    ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER

    @JsonPropertyDescription(GITLAB_CONFIG_DESCRIPTION)
    @Mixin
    GitlabTenantConfig gitlab

    @JsonPropertyDescription(SCMM_CONFIG_DESCRIPTION)
    @Mixin
    ScmManagerTenantConfig scmManager

    @JsonPropertyDescription(GITOPSUSERNAME_DESCRIPTION)
    String gitOpsUsername = ''

    @JsonIgnore
    Boolean internal = { ->
        return (gitlab.internal || scmManager.internal)
    }

    static class GitlabTenantConfig implements GitlabConfig {

        static final String GITLAB_INTERNAL_DESCRIPTION = 'True if Gitlab is running in the same K8s cluster. For now we only support access by external URL'
        static final String GITLAB_URL_DESCRIPTION = "Base URL for the Gitlab instance"
        static final String GITLAB_USERNAME_DESCRIPTION = 'Defaults to: oauth2.0 when PAT token is given.'
        static final String GITLAB_TOKEN_DESCRIPTION = 'PAT Token for the account. Needs read/write repo permissions. See docs for mor information'
        static final String GITLAB_PARENT_GROUP_ID = 'Number for the Gitlab Group where the repos and subgroups should be created'

        @JsonPropertyDescription(GITLAB_INTERNAL_DESCRIPTION)
        Boolean internal = false

        @Option(names = ['--gitlab-url'], description = GITLAB_URL_DESCRIPTION)
        @JsonPropertyDescription(GITLAB_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--gitlab-username'], description = GITLAB_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(GITLAB_USERNAME_DESCRIPTION)
        String username = 'oauth2.0'

        @Option(names = ['--gitlab-token'], description = GITLAB_TOKEN_DESCRIPTION)
        @JsonPropertyDescription(GITLAB_TOKEN_DESCRIPTION)
        String password = ''

        @Option(names = ['--gitlab-parent-id'], description = GITLAB_PARENT_GROUP_ID)
        @JsonPropertyDescription(GITLAB_PARENT_GROUP_ID)
        String parentGroupId = ''

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

    }


    static class ScmManagerTenantConfig implements ScmManagerConfig {

        static final String SCMM_SKIP_RESTART_DESCRIPTION = 'Skips restarting SCM-Manager after plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.\''
        static final String SCMM_SKIP_PLUGINS_DESCRIPTION = 'Skips plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.'
        static final String SCMM_URL_DESCRIPTION = 'The host of your external scm-manager'
        static final String SCMM_USERNAME_DESCRIPTION = 'Mandatory when scmm-url is set'
        static final String SCMM_PASSWORD_DESCRIPTION = 'Mandatory when scmm-url is set'
        static final String SCMM_ROOT_PATH_DESCRIPTION = 'Sets the root path for the Git Repositories. In SCM-Manager it is always "repo"'
        static final String SCMM_NAMESPACE_DESCRIPTION = 'Namespace where SCM-Manager should run'

        Boolean internal = true

        @Option(names = ['--scmm-url'], description = SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--scmm-namespace'], description = SCMM_NAMESPACE_DESCRIPTION)
        @JsonPropertyDescription(SCMM_NAMESPACE_DESCRIPTION)
        String namespace = 'scm-manager'

        @Option(names = ['--scmm-username'], description = SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = Config.DEFAULT_ADMIN_USER

        @Option(names = ['--scmm-password'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = Config.DEFAULT_ADMIN_PW

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        @JsonMerge
        Config.HelmConfigWithValues helm = new Config.HelmConfigWithValues(
                chart: 'scm-manager',
                repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                version: '3.11.0',
                values: [:]
        )

        @Option(names = ['--scmm-root-path'], description = SCMM_ROOT_PATH_DESCRIPTION)
        @JsonPropertyDescription(SCMM_ROOT_PATH_DESCRIPTION)
        String rootPath = 'repo'

        /* When installing from via Docker we have to distinguish scmm.url (which is a local IP address) from
           the SCMM URL used by jenkins.

           This is necessary to make the build on push feature (webhooks from SCMM to Jenkins that trigger builds) work
           in k3d.
           The webhook contains repository URLs that start with the "Base URL" Setting of SCMM.
           Jenkins checks these repo URLs and triggers all builds that match repo URLs.

           This value is set as "Base URL" in SCMM Settings and in Jenkins Job.

           See ApplicationConfigurator.addScmmConfig() and the comment at jenkins.urlForScmm */

        String urlForJenkins = ''

        @JsonIgnore
        String getHost() { return NetworkingUtils.getHost(url) }

        @JsonIgnore
        String getProtocol() { return NetworkingUtils.getProtocol(url) }
        String ingress = ''

        @Option(names = ['--scmm-skip-restart'], description = SCMM_SKIP_RESTART_DESCRIPTION)
        @JsonPropertyDescription(SCMM_SKIP_RESTART_DESCRIPTION)
        Boolean skipRestart = false

        @Option(names = ['--scmm-skip-plugins'], description = SCMM_SKIP_PLUGINS_DESCRIPTION)
        @JsonPropertyDescription(SCMM_SKIP_PLUGINS_DESCRIPTION)
        Boolean skipPlugins = false

        String gitOpsUsername = ''

        Credentials getCredentials() {
            return new Credentials(username, password)
        }
    }
}