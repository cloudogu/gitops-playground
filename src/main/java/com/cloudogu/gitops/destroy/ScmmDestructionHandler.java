package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManagerUrlResolver;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;

@Singleton
@Order(200)
public class ScmmDestructionHandler implements DestructionHandler {

    private final Config config;
    private final ContextBuilder contextBuilder;
    private final K8sClient k8sClient;
    private final NetworkingUtils networkingUtils;

    public ScmmDestructionHandler(Config config,
                                  ContextBuilder contextBuilder,
                                  K8sClient k8sClient,
                                  NetworkingUtils networkingUtils) {
        this.config = config;
        this.contextBuilder = contextBuilder;
        this.k8sClient = k8sClient;
        this.networkingUtils = networkingUtils;
    }

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
        ScmManagerUrlResolver urls = new ScmManagerUrlResolver(contextBuilder.build(),
                config.getScm().getScmManager(),
                k8sClient,
                networkingUtils);

        return new ScmManagerApiClient(urls.clientApiBase().toString(),
                config.getScm().getScmManager().getCredentials(),
                config.getApplication().getInsecure());
    }
}
