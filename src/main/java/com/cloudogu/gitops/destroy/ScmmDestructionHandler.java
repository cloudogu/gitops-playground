package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerUrlResolver;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import okhttp3.ResponseBody;
import retrofit2.Response;

import java.io.IOException;

@Singleton
@Order(200)
@RequiredArgsConstructor
public class ScmmDestructionHandler implements DestructionHandler {

	private static final String ARGOCD = "argocd";
	private static final String THIRD_PARTY_DEPENDENCIES = "3rd-party-dependencies";
	private static final int HTTP_NO_CONTENT = 204;
	private static final int HTTP_NOT_FOUND = 404;

	private final Config config;
	private final K8sClient k8sClient;
	private final NetworkingUtils networkingUtils;

	private ScmManagerApiClient scmmApiClient;

	@Override
	public void destroy() {
		deleteUser("gitops");
		deleteRepository(ARGOCD, ARGOCD);
		deleteRepository(ARGOCD, "cluster-resources");
		deleteRepository(ARGOCD, "example-apps");
		deleteRepository(THIRD_PARTY_DEPENDENCIES, "ces-build-lib", false);
		deleteRepository(THIRD_PARTY_DEPENDENCIES, "gitops-build-lib", false);
		deleteRepository(THIRD_PARTY_DEPENDENCIES, "spring-boot-helm-chart", false);
		deleteRepository(THIRD_PARTY_DEPENDENCIES, "spring-boot-helm-chart-with-dependency", false);
	}

	private void deleteRepository(String namespace, String repository, boolean prefixNamespace) {
		String namePrefix = prefixNamespace ? config.getApplication().getNamePrefix() : "";
		try {
			Response<Void> response = getScmmApiClient().repositoryApi()
														.delete(namePrefix + namespace, repository)
														.execute();
			if (response.code() != HTTP_NO_CONTENT && response.code() != HTTP_NOT_FOUND) {
				throw new IllegalStateException("Could not delete repository " + namespace + "/" + repository + " (" + response.code() + " " + response.message() + "): " + readErrorBody(
					response));
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to delete repository " + namespace + "/" + repository, e);
		}
	}

	private void deleteRepository(String namespace, String repository) {
		deleteRepository(namespace, repository, true);
	}

	private void deleteUser(String name) {
		try {
			Response<Void> response = getScmmApiClient().usersApi()
														.delete(config.getApplication().getNamePrefix() + name)
														.execute();
			if (response.code() != HTTP_NO_CONTENT && response.code() != HTTP_NOT_FOUND) {
				throw new IllegalStateException("Could not delete user " + name + " (" + response.code() + " " + response.message() + "): " + readErrorBody(
					response));
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to delete user " + name, e);
		}
	}

	private static String readErrorBody(Response<?> response) throws IOException {
		try (ResponseBody errorBody = response.errorBody()) {
			return errorBody != null ? errorBody.string() : "";
		}
	}

	private ScmManagerApiClient getScmmApiClient() {
		if (scmmApiClient == null) {
			ScmManagerUrlResolver urls = new ScmManagerUrlResolver(
				config.getScm().getScmManager(),
				k8sClient,
				networkingUtils,
				config.getApplication().getNamePrefix(),
				config.getApplication().getRunningInsideK8s()
			);

			scmmApiClient = new ScmManagerApiClient(
				urls.clientApiBase().toString(), config.getScm()
													   .getScmManager()
													   .getCredentials(), config.getApplication()
																				.getInsecure()
			);
		}
		return scmmApiClient;
	}
}
