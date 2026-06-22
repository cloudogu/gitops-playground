package com.cloudogu.gitops.infrastructure.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope
import com.cloudogu.gitops.infrastructure.git.providers.Scope
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import groovy.util.logging.Slf4j

import retrofit2.Response

@Slf4j
class ScmManagerProvider implements GitProvider {

	ScmManagerUrlResolver urls
	ScmManagerApiClient apiClient
	ScmManagerConfig scmmConfig

	NetworkingUtils networkingUtils
	K8sClient k8sClient
	Config config

	ScmManagerProvider(Config config,
			ScmManagerConfig scmmConfig,
			K8sClient k8sClient,
			NetworkingUtils networkingUtils) {
		this.scmmConfig = scmmConfig
		this.config = config
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils

		this.urls = new ScmManagerUrlResolver(this.config,
			this.scmmConfig,
			this.k8sClient,
			this.networkingUtils)
	}

	ScmManagerApiClient getApiClient() {
		if (this.apiClient == null) {
			this.apiClient = new ScmManagerApiClient(this.urls.clientApiBase().toString(),
				this.scmmConfig.credentials,
				this.config.application.insecure)
		}

		return this.apiClient
	}

	@Override
	boolean createRepository(String repoTarget, String description, boolean initialize = true) {
		def repoNamespace = repoTarget.split('/', 2)[0]
		def repoName = repoTarget.split('/', 2)[1]
		def repo = new Repository(repoNamespace, repoName, description ?: "")

		Response<Void> response = getApiClient().repositoryApi().create(repo, initialize).execute()
		return handle201or409(response, "Repository ${repoNamespace}/${repoName}")
	}

	@Override
	void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
		def repoNamespace = repoTarget.split('/', 2)[0]
		def repoName = repoTarget.split('/', 2)[1]

		boolean isGroup = (scope == Scope.GROUP)
		Permission.Role scmManagerRole = mapToScmManager(role)
		def permission = new Permission(principal, scmManagerRole, isGroup)

		Response<Void> response = getApiClient().repositoryApi()
			.createPermission(repoNamespace, repoName, permission)
			.execute()

		handle201or409(response, "Permission on ${repoNamespace}/${repoName}")
	}

	@Override
	Credentials getCredentials() {
		return this.scmmConfig.credentials
	}

	@Override
	String getGitOpsUsername() {
		return scmmConfig.gitOpsUsername
	}

	@Override
	String getUrl() {
		return urls.inClusterBase().toString()
	}

	@Override
	String repoPrefix() {
		return urls.inClusterRepoPrefix()
	}

	@Override
	String repoUrl(String repoTarget, RepoUrlScope scope) {
		switch (scope) {
			case RepoUrlScope.CLIENT:
				return urls.clientRepoUrl(repoTarget)
			case RepoUrlScope.IN_CLUSTER:
				return urls.inClusterRepoUrl(repoTarget)
			default:
				return urls.inClusterRepoUrl(repoTarget)
		}
	}

	@Override
	String getProtocol() {
		return urls.inClusterBase().scheme
	}

	@Override
	String getHost() {
		return urls.inClusterBase().host
	}

	@Override
	URI prometheusMetricsEndpoint() {
		return urls.prometheusEndpoint()
	}

	@Override
	void deleteRepository(String namespace, String repository, boolean prefixNamespace) {
		// intentionally left blank
	}

	@Override
	void deleteUser(String name) {
		// intentionally left blank
	}

	@Override
	void setDefaultBranch(String repoTarget, String branch) {
		// intentionally left blank
	}

	private static Permission.Role mapToScmManager(AccessRole role) {
		switch (role) {
			case AccessRole.READ:
				return Permission.Role.READ
			case AccessRole.WRITE:
				return Permission.Role.WRITE
			case AccessRole.MAINTAIN:
				log.warn("SCM-Manager: Mapping MAINTAIN to WRITE")
				return Permission.Role.WRITE
			case AccessRole.ADMIN:
				return Permission.Role.OWNER
			case AccessRole.OWNER:
				return Permission.Role.OWNER
			default:
				throw new IllegalArgumentException("Unsupported access role: ${role}")
		}
	}

	private static boolean handle201or409(Response<?> response, String what) {
		int code = response.code()

		if (code == 409) {
			log.debug("${what} already exists - ignoring HTTP 409")
			return false
		}

		if (code != 201) {
			throw new RuntimeException("Could not create ${what}. HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody()?.string()}")
		}

		return true
	}

	/**
	 * Test-only constructor.*/
	ScmManagerProvider(Config config,
			ScmManagerConfig scmmConfig,
			ScmManagerUrlResolver urls,
			ScmManagerApiClient apiClient) {
		this.scmmConfig = Objects.requireNonNull(scmmConfig, "scmmConfig must not be null")
		this.config = Objects.requireNonNull(config, "config must not be null")
		this.urls = Objects.requireNonNull(urls, "urls must not be null")
		this.apiClient = apiClient ?: new ScmManagerApiClient(urls.clientApiBase().toString(),
			scmmConfig.credentials,
			config.application.insecure)
	}
}