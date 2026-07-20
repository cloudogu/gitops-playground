package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerUrlResolver;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@Order(200)
@RequiredArgsConstructor
public class ScmmDestructionHandler implements DestructionHandler {

    private final Config config;
    private final ContextBuilder contextBuilder;
    private final K8sClient k8sClient;
    private final NetworkingUtils networkingUtils;

    private ScmManagerApiClient scmmApiClient;

    @Override
    public void destroy() {
        deleteUser("gitops");
        deleteRepository("argocd", "argocd");
        deleteRepository("argocd", "cluster-resources");
        deleteRepository("argocd", "example-apps");
        deleteRepository("3rd-party-dependencies", "ces-build-lib", false);
        deleteRepository("3rd-party-dependencies", "gitops-build-lib", false);
        deleteRepository("3rd-party-dependencies", "spring-boot-helm-chart", false);
        deleteRepository("3rd-party-dependencies", "spring-boot-helm-chart-with-dependency", false);
    }

    private void deleteRepository(String namespace, String repository, boolean prefixNamespace) {
        String namePrefix = prefixNamespace ? config.getApplication().getNamePrefix() : "";
        try {
            var response = getScmmApiClient().repositoryApi().delete(namePrefix + namespace, repository).execute();
            if (response.code() != 204) {
                throw new RuntimeException("Could not delete repository " + namespace + "/" + repository + " (" + response.code() + " " + response.message() + "): " + (response.errorBody() != null ? response.errorBody().string() : ""));
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
            var response = getScmmApiClient().usersApi().delete(config.getApplication().getNamePrefix() + name).execute();
            if (response.code() != 204) {
                throw new RuntimeException("Could not delete user " + name + " (" + response.code() + " " + response.message() + "): " + (response.errorBody() != null ? response.errorBody().string() : ""));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete user " + name, e);
        }
    }

    private ScmManagerApiClient getScmmApiClient() {
        if (scmmApiClient == null) {
            ScmManagerUrlResolver urls = new ScmManagerUrlResolver(contextBuilder.build(),
                    config.getScm().getScmManager(),
                    k8sClient,
                    networkingUtils);

            scmmApiClient = new ScmManagerApiClient(urls.clientApiBase().toString(),
                    config.getScm().getScmManager().getCredentials(),
                    config.getApplication().getInsecure());
        }
        return scmmApiClient;
    }
}
