package com.cloudogu.gitops.config.scm;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.GitlabConfig;
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import static com.cloudogu.gitops.config.ConfigConstants.KUBERNETES_SECRET_CREDENTIALS_DESCRIPTION;

public final class ScmCentralSchema {

	private ScmCentralSchema() {
	}

	@Getter
	@Setter
	public static class GitlabCentralConfig implements GitlabConfig {

		public static final String CENTRAL_GITLAB_URL_DESCRIPTION = "URL for external Gitlab";
		public static final String CENTRAL_GITLAB_USERNAME_DESCRIPTION = "GitLab username for API access. Must be 'oauth2' when using Personal Access Token (PAT) authentication";
		public static final String CENTRAL_GITLAB_PASSWORD_DESCRIPTION = "Password for SCM Manager authentication";
		public static final String CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION = "Main Group for Gitlab where the GOP creates it's groups/repos";
		public static final String CENTRAL_GITLAB_TECHNICAL_USERNAME_DESCRIPTION = "Username of an existing technical GitLab user that receives write access to repositories provisioned by GOP";

		@Option(names = {"--central-gitlab-url"}, description = CENTRAL_GITLAB_URL_DESCRIPTION)
		@JsonPropertyDescription(CENTRAL_GITLAB_URL_DESCRIPTION)
		private String url = "https://gitlab.com/";

		@Option(names = {"--central-gitlab-username"}, description = CENTRAL_GITLAB_USERNAME_DESCRIPTION)
		@JsonPropertyDescription(CENTRAL_GITLAB_USERNAME_DESCRIPTION)
		private String username = "oauth2.0";

		@Option(names = {"--central-gitlab-token"}, description = CENTRAL_GITLAB_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(CENTRAL_GITLAB_PASSWORD_DESCRIPTION)
		private String password = "";

		@JsonPropertyDescription(KUBERNETES_SECRET_CREDENTIALS_DESCRIPTION)
		private Credentials credentials;

		@Option(names = {"--central-gitlab-group-id"}, description = CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION)
		@JsonPropertyDescription(CENTRAL_GITLAB_PARENTGROUP_ID_DESCRIPTION)
		private String parentGroupId = "";

		@JsonPropertyDescription(CENTRAL_GITLAB_TECHNICAL_USERNAME_DESCRIPTION)
		private String technicalUsername = "";
		private String defaultVisibility = "";

		@Override
		public Credentials getCredentials() {
			return credentials != null ? credentials : new Credentials(username, password);
		}
	}

	@Getter
	@Setter
	public static class ScmManagerCentralConfig implements ScmManagerConfig {

		public static final String CENTRAL_SCMM_INTERNAL_DESCRIPTION = "SCM for Central Management is running on the same cluster, so k8s internal URLs can be used for access";
		public static final String CENTRAL_SCMM_URL_DESCRIPTION = "URL for the centralized Management Repo";
		public static final String CENTRAL_SCMM_USERNAME_DESCRIPTION = "CENTRAL SCMM username";
		public static final String CENTRAL_SCMM_PASSWORD_DESCRIPTION = "CENTRAL SCMM password";
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

		@JsonPropertyDescription(KUBERNETES_SECRET_CREDENTIALS_DESCRIPTION)
		private Credentials credentials;

		@JsonPropertyDescription("Credentials of the existing central SCM-Manager metrics user used by Prometheus")
		@Mixin
		private ScmManagerMetricsUserConfig metricsUser = new ScmManagerMetricsUserConfig();

		@Option(names = {"--central-scmm-namespace"}, description = CENTRAL_SCMM_NAMESPACE_DESCRIPTION)
		@JsonPropertyDescription(CENTRAL_SCMM_NAMESPACE_DESCRIPTION)
		private String namespace = "scm-manager";

		private String gopManagedTechnicalUsername = "";

		@Override
		@JsonIgnore
		public String getMetricsUsername() {
			return metricsUser == null ? null : metricsUser.getUsername();
		}

		@Override
		@JsonIgnore
		public String getMetricsPassword() {
			return metricsUser == null ? null : metricsUser.getPassword();
		}

		@Override
		@JsonIgnore
		public Credentials getMetricsCredentials() {
			return metricsUser == null ? null : metricsUser.getCredentials();
		}

		@Override
		public String getIngress() {
			return null; // Needed for setup
		}

		@Override
		public Config.HelmConfigWithValues getHelm() {
			return null; // Needed for setup
		}

		@Override
		public Credentials getCredentials() {
			return credentials != null ? credentials : new Credentials(username, password);
		}

		@Getter
		@Setter
		public static class ScmManagerMetricsUserConfig {

			@Option(names = {"--central-scmm-metrics-username"}, description = "Username of the existing central SCM-Manager metrics user used by Prometheus")
			@JsonPropertyDescription("Username of the existing central SCM-Manager metrics user used by Prometheus")
			private String username = "";

			@Option(names = {"--central-scmm-metrics-password"}, description = "Password of the existing central SCM-Manager metrics user used by Prometheus")
			@JsonPropertyDescription("Password of the existing central SCM-Manager metrics user used by Prometheus")
			private String password = "";

			@JsonPropertyDescription(KUBERNETES_SECRET_CREDENTIALS_DESCRIPTION)
			private Credentials credentials;

			public Credentials getCredentials() {
				return credentials != null ? credentials : new Credentials(username, password);
			}
		}
	}
}
