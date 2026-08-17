package com.cloudogu.gitops.infrastructure.git.providers.scmmanager;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.Repository;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import com.cloudogu.gitops.utils.Tuple;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

@Slf4j
public class ScmManagerProvider implements GitProvider {

	private static final int HTTP_CREATED = 201;
	private static final int HTTP_CONFLICT = 409;

	private ScmManagerUrlResolver urls;
	private ScmManagerApiClient apiClient;
	private final ScmManagerConfig scmmConfig;

	private final NetworkingUtils networkingUtils;
	private final K8sClient k8sClient;
	private final DeploymentContext context;

	public ScmManagerProvider(
		DeploymentContext context,
		ScmManagerConfig scmmConfig,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils) {
		this(context, scmmConfig, k8sClient, networkingUtils, "");
	}

	public ScmManagerProvider(
		DeploymentContext context,
		ScmManagerConfig scmmConfig,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils,
		String servicePrefix) {
		this.scmmConfig = scmmConfig;
		this.context = context;
		this.k8sClient = k8sClient;
		this.networkingUtils = networkingUtils;

		Config config = this.context.getConfig();
		this.urls = new ScmManagerUrlResolver(
			this.scmmConfig,
			this.k8sClient,
			this.networkingUtils,
			config.getApplication().getNamePrefix(),
			config.getApplication().getRunningInsideK8s(),
			servicePrefix
		);
	}

	public ScmManagerConfig getScmmConfig() {
		return scmmConfig;
	}

	public Config getConfig() {
		return context.getConfig();
	}

	public ScmManagerApiClient getApiClient() {
		if (this.apiClient == null) {
			this.apiClient = new ScmManagerApiClient(
				this.urls.clientApiBase()
				         .toString(), this.scmmConfig.getCredentials(), this.getConfig()
				                                                            .getApplication()
				                                                            .getInsecure()
			);
		}

		return this.apiClient;
	}

	@Override
	public boolean createRepository(String repoTarget, String description, boolean initialize) {
		Tuple<String, String> target = GitProvider.splitRepoTarget(repoTarget);
		String repoNamespace = target.getFirst();
		String repoName = target.getSecond();
		Repository repo = new Repository(repoNamespace, repoName, description != null ? description : "");

		try {
			Response<Void> response = getApiClient().repositoryApi().create(repo, initialize).execute();
			return handle201or409(response, "Repository " + repoNamespace + "/" + repoName);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create repository " + repoTarget, e);
		}
	}

	@Override
	public void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
		Tuple<String, String> target = GitProvider.splitRepoTarget(repoTarget);
		String repoNamespace = target.getFirst();
		String repoName = target.getSecond();

		boolean isGroup = (scope == Scope.GROUP);
		Permission.Role scmManagerRole = mapToScmManager(role);
		Permission permission = new Permission(principal, scmManagerRole, isGroup);

		try {
			Response<Void> response = getApiClient().repositoryApi()
			                                        .createPermission(repoNamespace, repoName, permission)
			                                        .execute();

			handle201or409(response, "Permission on " + repoNamespace + "/" + repoName);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to set permission on repository " + repoTarget, e);
		}
	}

	@Override
	public Credentials getCredentials() {
		return this.scmmConfig.getCredentials();
	}

	@Override
	public String getGitOpsUsername() {
		return scmmConfig.getGitOpsUsername();
	}

	@Override
	public String getUrl() {
		return urls.inClusterBase().toString();
	}

	@Override
	public String repoPrefix() {
		return urls.inClusterRepoPrefix();
	}

	@Override
	public String repoUrl(String repoTarget, RepoUrlScope scope) {
		return switch (scope) {
			case CLIENT -> urls.clientRepoUrl(repoTarget);
			case IN_CLUSTER -> urls.inClusterRepoUrl(repoTarget);
		};
	}

	@Override
	public String getProtocol() {
		return urls.inClusterBase().getScheme();
	}

	@Override
	public String getHost() {
		return urls.inClusterBase().getHost();
	}

	@Override
	public URI prometheusMetricsEndpoint() {
		return urls.prometheusEndpoint();
	}

	private static Permission.Role mapToScmManager(AccessRole role) {
		switch (role) {
			case READ:
				return Permission.Role.READ;
			case WRITE:
				return Permission.Role.WRITE;
			case MAINTAIN:
				log.warn("SCM-Manager: Mapping MAINTAIN to WRITE");
				return Permission.Role.WRITE;
			case ADMIN:
				return Permission.Role.OWNER;
			case OWNER:
				return Permission.Role.OWNER;
			default:
				throw new IllegalArgumentException("Unsupported access role: " + role);
		}
	}

	private static boolean handle201or409(Response<Void> response, String resourceName) {
		if (response.code() == HTTP_CREATED) {
			log.debug("{} created successfully", resourceName);
			return true;
		}

		if (response.code() == HTTP_CONFLICT) {
			log.debug("{} already exists", resourceName);
			return false;
		}

		throw new IllegalStateException("Failed to create " + resourceName + ". HTTP Status: " + response.code() + " - " + response.message());
	}
}
