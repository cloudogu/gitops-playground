package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.credentials.CredentialsResolver;
import com.cloudogu.gitops.application.credentials.ResolvedCredentials;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.ScmProviderType;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.gitlab.GitlabProvider;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerProvider;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class GitHandler {

	@Getter
	private final K8sClient k8sClient;

	@Getter
	private final NetworkingUtils networkingUtils;

	private final Config config;
	private final CredentialsResolver credentialsResolver;

	@Getter
	@Setter
	private GitProvider tenant;

	@Getter
	@Setter
	private GitProvider central;

	public void validate() {
		boolean gitlabRequested = config.getScm().getScmProviderType() == ScmProviderType.GITLAB;
		boolean gitlabUrlConfigured = config.getScm().getGitlab() != null && !StringUtils.isEmpty(config.getScm()
																					.getGitlab()
																					.getUrl());
		if (gitlabRequested || gitlabUrlConfigured) {
			config.getScm().setScmProviderType(ScmProviderType.GITLAB);
			config.getScm().setScmManager(null);

			var gitlab = config.getScm().getGitlab();
			if (gitlab == null || StringUtils.isEmpty(gitlab.getUrl())
				|| !credentialsConfigured(gitlab.getCredentials(), gitlab.getPassword())
				|| StringUtils.isEmpty(gitlab.getParentGroupId())) {
				throw new IllegalArgumentException(
					"GitLab configuration incomplete: please provide url, credentials and parentGroupId");
			}
			return;
		}

		config.getScm().setScmProviderType(ScmProviderType.SCM_MANAGER);
		if (config.getScm().getScmManager() != null) {
			String prefix = config.getApplication().getNamePrefix();
			if (prefix == null) {
				prefix = "";
			}
			config.getScm().getScmManager().setGitOpsUsername(prefix + "gitops");
		}
	}

	public void prepareProviders(DeploymentContext context) {
		this.tenant = createTenantScmProvider();

		if (context.isMultiTenant()) {
			this.central = createCentralScmProvider();
		}
	}

	public GitProvider getResourcesScm() {
		if (central != null) {
			return central;
		}

		if (tenant != null) {
			return tenant;
		}

		throw new IllegalStateException("No SCM provider found.");
	}

	private GitProvider createTenantScmProvider() {
		return switch (config.getScm().getScmProviderType()) {
			case GITLAB -> {
				var gitlab = config.getScm().getGitlab();
				yield new GitlabProvider(
					gitlab,
					resolveRuntimeCredentials(
						gitlab.getCredentials(), gitlab.getUsername(), gitlab.getPassword()
					),
					config.getApplication().getNamePrefix()
				);
			}
			case SCM_MANAGER -> {
				String prefix = config.getApplication().getNamePrefix();
				if (prefix == null) {
					prefix = "";
				}
				var scmManager = config.getScm().getScmManager();
				yield new ScmManagerProvider(
					scmManager,
					resolveRuntimeCredentials(
						scmManager.getCredentials(), scmManager.getUsername(), scmManager.getPassword()
					),
					k8sClient,
					networkingUtils,
					config.getApplication().getNamePrefix(),
					config.getApplication().getRunningInsideK8s(),
					config.getApplication().getInsecure(),
					prefix
				);
			}
			default ->
				throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM: " + config.getScm()
																											   .getScmProviderType());
		};
	}

	private GitProvider createCentralScmProvider() {
		return switch (config.getMultiTenant().getScmProviderType()) {
			case GITLAB -> {
				var gitlab = config.getMultiTenant().getGitlab();
				yield new GitlabProvider(
					gitlab,
					resolveRuntimeCredentials(
						gitlab.getCredentials(), gitlab.getUsername(), gitlab.getPassword()
					),
					config.getApplication().getNamePrefix()
				);
			}
			case SCM_MANAGER -> {
				var scmManager = config.getMultiTenant().getScmManager();
				yield new ScmManagerProvider(
					scmManager,
					resolveRuntimeCredentials(
						scmManager.getCredentials(), scmManager.getUsername(), scmManager.getPassword()
					),
					k8sClient,
					networkingUtils,
					config.getApplication().getNamePrefix(),
					config.getApplication().getRunningInsideK8s(),
					config.getApplication().getInsecure(),
					centralScmManagerServicePrefix(config)
				);
			}
			default -> throw new IllegalArgumentException("Unsupported SCM-Central provider: " + config.getMultiTenant()
																													   .getScmProviderType());
		};
	}

	private Credentials resolveRuntimeCredentials(
		Credentials reference,
		String fallbackUsername,
		String fallbackPassword) {
		ResolvedCredentials resolved = credentialsResolver.resolve(reference, fallbackUsername, fallbackPassword);
		return new Credentials(resolved.username(), resolved.password());
	}

	private static boolean credentialsConfigured(Credentials reference, String fallbackPassword) {
		if (hasText(fallbackPassword)) {
			return true;
		}

		return reference != null
			&& hasText(reference.getSecretName())
			&& hasText(reference.getSecretNamespace());
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String centralScmManagerServicePrefix(Config config) {
		String namespace = config.getMultiTenant().getScmManager().getNamespace();
		if (namespace == null) {
			namespace = "";
		}
		namespace = namespace.strip();
		String baseNamespace = "scm-manager";

		if (namespace.equals(baseNamespace) || !namespace.endsWith(baseNamespace)) {
			return "";
		}

		return namespace.substring(0, namespace.length() - baseNamespace.length());
	}
}
