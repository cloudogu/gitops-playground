package com.cloudogu.gitops.config.scm;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.GitlabConfig;
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.HashMap;

import static com.cloudogu.gitops.config.ConfigConstants.HELM_CONFIG_DESCRIPTION;

@Getter
@Setter
@NoArgsConstructor
public class ScmTenantSchema {

	public static final String GITLAB_CONFIG_DESCRIPTION = "Config for GITLAB";
	public static final String SCMM_CONFIG_DESCRIPTION = "Config for SCM-Manager";
	public static final String SCM_PROVIDER_TYPE_DESCRIPTION = "The SCM provider type. Possible values: SCM_MANAGER, GITLAB";
	public static final String GITOPSUSERNAME_DESCRIPTION = "Username for the Gitops User";

	@Option(names = {"--scm-provider"}, description = SCM_PROVIDER_TYPE_DESCRIPTION, defaultValue = "SCM_MANAGER")
	@JsonPropertyDescription(SCM_PROVIDER_TYPE_DESCRIPTION)
	private ScmProviderType scmProviderType = ScmProviderType.SCM_MANAGER;

	@JsonPropertyDescription(GITLAB_CONFIG_DESCRIPTION)
	@Mixin
	private GitlabTenantConfig gitlab;

	@JsonPropertyDescription(SCMM_CONFIG_DESCRIPTION)
	@Mixin
	private ScmManagerTenantConfig scmManager;

	@JsonIgnore
	public Boolean getInternal() {
		return (gitlab != null && gitlab.getInternal()) || (scmManager != null && scmManager.getInternal());
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class GitlabTenantConfig implements GitlabConfig {

		public static final String GITLAB_INTERNAL_DESCRIPTION = "True if Gitlab is running in the same K8s cluster. For now we only support access by external URL";
		public static final String GITLAB_URL_DESCRIPTION = "Base URL for the Gitlab instance";
		public static final String GITLAB_USERNAME_DESCRIPTION = "Defaults to: oauth2.0 when PAT token is given.";
		public static final String GITLAB_TOKEN_DESCRIPTION = "PAT Token for the account. Needs read/write repo permissions. See docs for mor information";
		public static final String GITLAB_PARENT_GROUP_ID = "Number for the Gitlab Group where the repos and subgroups should be created";

		@JsonPropertyDescription(GITLAB_INTERNAL_DESCRIPTION)
		private Boolean internal = false;

		@Option(names = {"--gitlab-url"}, description = GITLAB_URL_DESCRIPTION)
		@JsonPropertyDescription(GITLAB_URL_DESCRIPTION)
		private String url;

		@Option(names = {"--gitlab-username"}, description = GITLAB_USERNAME_DESCRIPTION)
		@JsonPropertyDescription(GITLAB_USERNAME_DESCRIPTION)
		private String username = "oauth2.0";

		@Option(names = {"--gitlab-token"}, description = GITLAB_TOKEN_DESCRIPTION)
		@JsonPropertyDescription(GITLAB_TOKEN_DESCRIPTION)
		private String password;

		@JsonPropertyDescription(GITLAB_URL_DESCRIPTION)
		private Credentials credentials;

		@Option(names = {"--gitlab-group-id"}, description = GITLAB_PARENT_GROUP_ID)
		@JsonPropertyDescription(GITLAB_PARENT_GROUP_ID)
		private String parentGroupId = "";

		@JsonPropertyDescription(GITOPSUSERNAME_DESCRIPTION)
		private String gitOpsUsername = "";

		private String defaultVisibility = "";

		@Override
		@JsonIgnore
		public Credentials getCredentials() {
			return new Credentials(username, password);
		}

		public void setCredentials(Credentials credentials) {
			if (credentials != null && credentials.isUsed()) {
				this.credentials = new K8sClient().getCredentialsFromSecret(credentials);
				this.username = credentials.getUsername();
				this.password = credentials.getPassword();
			}
		}

	}

	@Getter
	@Setter
	public static class ScmManagerTenantConfig implements ScmManagerConfig {

		public static final String SCMM_SKIP_RESTART_DESCRIPTION = "Skips restarting SCM-Manager after plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.'";
		public static final String SCMM_SKIP_PLUGINS_DESCRIPTION = "Skips plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.";
		public static final String SCMM_URL_DESCRIPTION = "The host of your external scm-manager";
		public static final String SCMM_USERNAME_DESCRIPTION = "Mandatory when scmm-url is set";
		public static final String SCMM_PASSWORD_DESCRIPTION = "Mandatory when scmm-url is set";
		public static final String SCMM_NAMESPACE_DESCRIPTION = "Namespace where SCM-Manager should run";
		public static final String SCMM_IMAGE = "Sets image for SCM-Manager";

		private Boolean internal = true;

		@Option(names = {"--scmm-url"}, description = SCMM_URL_DESCRIPTION)
		@JsonPropertyDescription(SCMM_URL_DESCRIPTION)
		private String url = "";

		@Option(names = {"--scmm-namespace"}, description = SCMM_NAMESPACE_DESCRIPTION)
		@JsonPropertyDescription(SCMM_NAMESPACE_DESCRIPTION)
		private String namespace = "scm-manager";

		@Option(names = {"--scmm-username"}, description = SCMM_USERNAME_DESCRIPTION)
		@JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
		private String username = Config.DEFAULT_ADMIN_USER;

		@Option(names = {"--scmm-password"}, description = SCMM_PASSWORD_DESCRIPTION)
		@JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
		private String password = Config.DEFAULT_ADMIN_PW;

		@JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
		private Credentials credentials;

		@JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
		@JsonMerge
		private Config.HelmConfigWithValues helm;

		@Option(names = {"--scmm-image"}, description = SCMM_IMAGE)
		@JsonPropertyDescription(SCMM_IMAGE)
		private String scmmImage = "";

		private String urlForJenkins = "";
		private String ingress = "";

		@Option(names = {"--scmm-skip-restart"}, description = SCMM_SKIP_RESTART_DESCRIPTION)
		@JsonPropertyDescription(SCMM_SKIP_RESTART_DESCRIPTION)
		private Boolean skipRestart = false;

		@Option(names = {"--scmm-skip-plugins"}, description = SCMM_SKIP_PLUGINS_DESCRIPTION)
		@JsonPropertyDescription(SCMM_SKIP_PLUGINS_DESCRIPTION)
		private Boolean skipPlugins = false;

		@JsonPropertyDescription(GITOPSUSERNAME_DESCRIPTION)
		private String gitOpsUsername = "";

		public ScmManagerTenantConfig() {
			helm = new Config.HelmConfigWithValues();
			helm.setChart("scm-manager");
			helm.setRepoURL("https://packages.scm-manager.org/repository/helm-v2-releases/");
			// renovate: depName=scm-manager registryUrl=https://packages.scm-manager.org/repository/helm-v2-releases/
			helm.setVersion("3.11.10");
			helm.setValues(new HashMap<>());
		}

		public void setCredentials(Credentials credentials) {
			if (credentials != null && credentials.isUsed()) {
				this.credentials = new K8sClient().getCredentialsFromSecret(credentials);
				this.username = credentials.getUsername();
				this.password = credentials.getPassword();
			}
		}

		@Override
		@JsonIgnore
		public Credentials getCredentials() {
			return new Credentials(username, password);
		}
	}
}
