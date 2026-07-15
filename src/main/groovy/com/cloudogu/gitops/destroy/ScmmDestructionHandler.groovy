package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerUrlResolver
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import io.micronaut.core.annotation.Order

import jakarta.inject.Singleton

@Singleton
@Order(200)
class ScmmDestructionHandler implements DestructionHandler {
	private Config config
	private ContextBuilder contextBuilder
	private K8sClient k8sClient
	private NetworkingUtils networkingUtils

	ScmmDestructionHandler(Config config,
		ContextBuilder contextBuilder,
		K8sClient k8sClient,
		NetworkingUtils networkingUtils) {
		this.config = config
		this.contextBuilder = contextBuilder
		this.k8sClient = k8sClient
		this.networkingUtils = networkingUtils
	}

	@Override
	void destroy() {
		deleteUser('gitops')
		deleteRepository('argocd', "argocd")
		deleteRepository('argocd', 'cluster-resources')
		deleteRepository('argocd', 'example-apps')
		deleteRepository('3rd-party-dependencies', 'ces-build-lib', false)
		deleteRepository('3rd-party-dependencies', 'gitops-build-lib', false)
		deleteRepository('3rd-party-dependencies', 'spring-boot-helm-chart', false)
		deleteRepository('3rd-party-dependencies', 'spring-boot-helm-chart-with-dependency', false)
	}

	private void deleteRepository(String namespace, String repository, boolean prefixNamespace = true) {
		def namePrefix = prefixNamespace ? config.application.namePrefix : ''
		def response = scmmApiClient.repositoryApi().delete("${namePrefix}$namespace", repository).execute()

		if (response.code() != 204) {
			throw new RuntimeException("Could not delete user $namespace/$repository (${response.code()} ${response.message()}): ${response.errorBody().string()}")
		}
	}

	private void deleteUser(String name) {
		def response = scmmApiClient.usersApi().delete("${config.application.namePrefix}$name").execute()

		if (response.code() != 204) {
			throw new RuntimeException("Could not delete user $name (${response.code()} ${response.message()}): ${response.errorBody().string()}")
		}
	}

	private Config getConfig() { return config }

	private ScmManagerApiClient getScmmApiClient() {
		def urls = new ScmManagerUrlResolver(contextBuilder.build(),
			config.scm.scmManager,
			k8sClient,
			networkingUtils)

		return new ScmManagerApiClient(urls.clientApiBase().toString(),
			config.scm.scmManager.credentials,
			config.application.insecure)
	}
}