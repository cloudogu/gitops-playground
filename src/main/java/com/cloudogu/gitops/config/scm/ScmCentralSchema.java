package com.cloudogu.gitops.config.scm;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.GitlabConfig;
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Option;

public class ScmCentralSchema {

    @Getter
    @Setter
    public static class GitlabCentralConfig implements GitlabConfig {

        public static final String CENTRAL_GITLAB_URL_DESCRIPTION = "URL for external Gitlab";
        public static final String CENTRAL_GITLAB_USERNAME_DESCRIPTION = "GitLab username for API access. Must be 'oauth2' when using Personal Access Token (PAT) authentication";
        public static final String CENTRAL_GITLAB_PASSWORD_DESCRIPTION = "Password for SCM Manager authentication";
        public static final String CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION = "Main Group for Gitlab where the GOP creates it's groups/repos";

        @Option(names = {"--central-gitlab-url"}, description = CENTRAL_GITLAB_URL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_URL_DESCRIPTION)
        private String url = "https://gitlab.com/";

        @Option(names = {"--central-gitlab-username"}, description = CENTRAL_GITLAB_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_USERNAME_DESCRIPTION)
        private String username = "oauth2.0";

        @Option(names = {"--central-gitlab-token"}, description = CENTRAL_GITLAB_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_PASSWORD_DESCRIPTION)
        private String password = "";

        @Option(names = {"--central-gitlab-group-id"}, description = CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION)
        private String parentGroupId = "";

        private String gitOpsUsername = "";
        private String defaultVisibility = "";

        @Override
        public Credentials getCredentials() {
            return new Credentials(username, password);
        }
    }

    @Getter
    @Setter
    public static class ScmManagerCentralConfig implements ScmManagerConfig {

        public static final String CENTRAL_SCMM_INTERNAL_DESCRIPTION = "SCM for Central Management is running on the same cluster, so k8s internal URLs can be used for access";
        public static final String CENTRAL_SCMM_URL_DESCRIPTION = "URL for the centralized Management Repo";
        public static final String CENTRAL_SCMM_USERNAME_DESCRIPTION = "CENTRAL SCMM username";
        public static final String CENTRAL_SCMM_PASSWORD_DESCRIPTION = "CENTRAL SCMM password";
        public static final String CENTRAL_SCMM_PATH_DESCRIPTION = "Root path for SCM Manager. In SCM-Manager it is always \"repo\"";
        public static final String CENTRAL_SCMM_NAMESPACE_DESCRIPTION = "Namespace where to find the Central SCMM";

        @Option(names = {"--central-scmm-internal"}, description = CENTRAL_SCMM_INTERNAL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_INTERNAL_DESCRIPTION)
        private Boolean internal = false;

        @Option(names = {"--central-scmm-url"}, description = CENTRAL_SCMM_URL_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_URL_DESCRIPTION)
        private String url = "";

        @Option(names = {"--central-scmm-username"}, description = CENTRAL_SCMM_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_USERNAME_DESCRIPTION)
        private String username = "";

        @Option(names = {"--central-scmm-password"}, description = CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_PASSWORD_DESCRIPTION)
        private String password = "";

        @Option(names = {"--central-scmm-namespace"}, description = CENTRAL_SCMM_NAMESPACE_DESCRIPTION)
        @JsonPropertyDescription(CENTRAL_SCMM_NAMESPACE_DESCRIPTION)
        private String namespace = "scm-manager";

        private String gitOpsUsername = "";

        @Override
        public String getIngress() {
            return null; //Needed for setup
        }

        @Override
        public Config.HelmConfigWithValues getHelm() {
            return null; //Needed for setup
        }

        @Override
        public Credentials getCredentials() {
            return new Credentials(username, password);
        }
    }
}
