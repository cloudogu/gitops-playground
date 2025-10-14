package com.cloudogu.gitops.features.git.config

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.utils.NetworkingUtils
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

import static com.cloudogu.gitops.config.ConfigConstants.*

class ScmTenantSchema {

    @Option(
            names = ['--scm-provider'],
            description = "The SCM provider type. Possible values: SCM_MANAGER, GITLAB",
            defaultValue = "SCM_MANAGER"
    )
    ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER

    @JsonPropertyDescription("GitlabConfig")
    @Mixin
    GitlabTenantConfig gitlab

    @JsonPropertyDescription("scmmTenantConfig")
    @Mixin
    ScmManagerTenantConfig scmManager

    String gitOpsUsername = ''

    @JsonIgnore
    Boolean internal = { ->
        return (gitlab.internal || scmManager.internal)
    }

    static class GitlabTenantConfig implements GitlabConfig {

        @JsonPropertyDescription(Description.GITLAB_INTERNAL)
        Boolean internal = false

        @Option(names = ['--gitlab-url'], description = Description.GITLAB_URL_DESCRIPTION)
        @JsonPropertyDescription(Description.GITLAB_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--gitlab-username'], description = Description.GITLAB_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(Description.GITLAB_USERNAME_DESCRIPTION)
        String username = 'oauth2.0'

        @Option(names = ['--gitlab-token'], description = Description.GITLAB_TOKEN_DESCRIPTION)
        @JsonPropertyDescription(Description.GITLAB_TOKEN_DESCRIPTION)
        String password = ''

        @Option(names = ['--gitlab-parent-id'], description = Description.GITLAB_PARENT_GROUP_ID)
        @JsonPropertyDescription(Description.GITLAB_PARENT_GROUP_ID)
        String parentGroupId = ''

        Credentials getCredentials() {
            return new Credentials(username, password)
        }

        static final class Description {
            String GITLAB_INTERNAL = 'True if Gitlab is running in the same K8s cluster. For now we only support access by external URL'
            String GITLAB_URL_DESCRIPTION = "Base URL for the Gitlab instance"
            String GITLAB_USERNAME_DESCRIPTION = 'Gitlab Username.'
            String GITLAB_TOKEN_DESCRIPTION = 'PAT Token for the account. Needs read/write repo permissions. See docs for mor information'
            String GITLAB_PARENT_GROUP_ID = 'Number for the Gitlab Group where the repos and subgroups should be created'
        }
    }


    static class ScmManagerTenantConfig implements ScmManagerConfig {
        Boolean internal = true

        @Option(names = ['--scmm-url'], description = SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--scm-namespace'], description = 'Namespace where the tenant scm resides in')
        @JsonPropertyDescription(CENTRAL_ARGOCD_NAMESPACE_DESCRIPTION)
        String namespace = 'scm-manager'

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
                version: '3.10.3',
                values: [:]
        )

        @Option(names = ['--scm-root-path'], description = SCM_ROOT_PATH_DESCRIPTION)
        @JsonPropertyDescription(SCM_ROOT_PATH_DESCRIPTION)
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

        @Override
        boolean isInternal() {
            return internal != null ? internal : Boolean.TRUE
        }

        Credentials getCredentials() {
            return new Credentials(username, password)
        }
    }
}