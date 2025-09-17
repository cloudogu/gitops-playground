package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmmApiClient
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Singleton
@Order(200)
class ScmmDestructionHandler implements DestructionHandler {
    private ScmmApiClient scmmApiClient
    private Config config

    ScmmDestructionHandler(
            Config config,
            ScmmApiClient scmmApiClient
    ) {
        this.config = config
        this.scmmApiClient = scmmApiClient
    }

    @Override
    void destroy() {
        deleteUser("gitops")
        deleteRepository("argocd", "argocd")
        deleteRepository("argocd", "cluster-resources")
        deleteRepository("argocd", "example-apps")
        deleteRepository("argocd", "nginx-helm-jenkins")
        deleteRepository("argocd", "petclinic-plain")
        deleteRepository("argocd", "petclinic-helm")
        deleteRepository("exercises", "broken-application")
        deleteRepository("exercises", "nginx-validation")
        deleteRepository("exercises", "petclinic-helm")
        deleteRepository("3rd-party-dependencies", "ces-build-lib", false)
        deleteRepository("3rd-party-dependencies", "gitops-build-lib", false)
        deleteRepository("3rd-party-dependencies", "spring-boot-helm-chart", false)
        deleteRepository("3rd-party-dependencies", "spring-boot-helm-chart-with-dependency", false)
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
}
