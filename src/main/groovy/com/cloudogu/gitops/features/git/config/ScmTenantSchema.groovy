package com.cloudogu.gitops.features.git.config

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.utils.NetworkingUtils
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Option
import picocli.CommandLine.Mixin

import static com.cloudogu.gitops.config.ConfigConstants.*

class ScmTenantSchema {

    //TODO type via setter if gitlabConfig is set
    ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER

    @JsonPropertyDescription("GitlabConfig")
    @Mixin
    GitlabTenantConfig gitlabConfig

    @JsonPropertyDescription("GitlabConfig")
    @Mixin
    ScmManagerTenantConfig scmmConfig

    @JsonIgnore
    Boolean isInternal = { ->
        return (gitlabConfig.internal || scmmConfig.internal)
    }

    static class GitlabTenantConfig implements GitlabConfig {
        // Only supports external Gitlab for now
        Boolean internal = false

        @Option(names = ['--gitlab-url'], description = SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--gitlab-username'], description = SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = 'oauth2.0'

        @Option(names = ['--gitlab-token'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = ''

        @Option(names = ['--gitlab-parent-id'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String parentProjectid = ''

        String gitOpsUsername = ''

        Credentials getCredentials() {
            return new Credentials(username, password)
        }
    }

    static class ScmManagerTenantConfig implements ScmManagerConfig {
        Boolean internal = true

        @Option(names = ['--scmm-url'], description = SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--scmm-username'], description = SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = Config.DEFAULT_ADMIN_USER

        @Option(names = ['--scmm-password'], description = SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = Config.DEFAULT_ADMIN_PW

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        Config.HelmConfigWithValues helm = new Config.HelmConfigWithValues(
                chart: 'scm-manager',
                repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                version: '3.8.0',
                values: [:]
        )

        @Option(names = ['--scm-root-path'], description = SCM_ROOT_PATH_DESCRIPTION)
        @JsonPropertyDescription(SCM_ROOT_PATH_DESCRIPTION)
        String rootPath = 'repo'

        String gitOpsUsername = ''
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

        @JsonIgnore
        Credentials getCredentials() {
            return new Credentials(username, password)
        }
    }
}