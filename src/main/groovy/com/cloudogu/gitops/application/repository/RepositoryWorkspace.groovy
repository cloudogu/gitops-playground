package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo
import groovy.transform.CompileStatic

import java.nio.file.Path

@CompileStatic
class RepositoryWorkspace {

    private static final String APPS_DIR = 'apps'
    final GitRepo clusterResourcesRepo
    final GitRepo tenantBootstrapRepo

    RepositoryWorkspace(
            GitRepo clusterResourcesRepo,
            GitRepo tenantBootstrapRepo = null
    ) {
        this.clusterResourcesRepo = clusterResourcesRepo
        this.tenantBootstrapRepo = tenantBootstrapRepo
    }

    boolean hasTenantBootstrapRepo() {
        return tenantBootstrapRepo != null
    }

    GitRepo tenantBootstrapRepoOrFail() {
        if (tenantBootstrapRepo == null) {
            throw new IllegalStateException('Tenant bootstrap repository is not available in single-instance mode.')
        }

        return tenantBootstrapRepo
    }

    void prepareLocalDirectories() {
        Path.of(clusterRootDir()).toFile().mkdirs()
        Path.of(clusterAppsDir()).toFile().mkdirs()

        if (hasTenantBootstrapRepo()) {
            Path.of(tenantRootDir()).toFile().mkdirs()
            Path.of(tenantAppsDir()).toFile().mkdirs()
        }
    }

    void cloneRepositories() {
        clusterResourcesRepo.cloneRepo()

        if (hasTenantBootstrapRepo()) {
            tenantBootstrapRepoOrFail().cloneRepo()
        }
    }

    void initLocalRepositoriesIfNeeded() {
        clusterResourcesRepo.initLocalRepoIfNeeded()

        if (hasTenantBootstrapRepo()) {
            tenantBootstrapRepoOrFail().initLocalRepoIfNeeded()
        }
    }

    String clusterRootDir() {
        return clusterResourcesRepo.absoluteLocalRepoTmpDir
    }

    String clusterAppsDir() {
        return Path.of(clusterRootDir(), APPS_DIR).toString()
    }

    String clusterAppDir(String toolName) {
        return Path.of(clusterAppsDir(), toolName).toString()
    }

    String tenantRootDir() {
        return tenantBootstrapRepoOrFail().absoluteLocalRepoTmpDir
    }

    String tenantAppsDir() {
        return Path.of(tenantRootDir(), APPS_DIR).toString()
    }

    String tenantAppDir(String toolName) {
        return Path.of(tenantAppsDir(), toolName).toString()
    }

    String clusterResourcesRepoUrl() {
        return "${clusterResourcesRepo.gitProvider.repoPrefix()}argocd/cluster-resources.git"
    }

    void writeClusterFile(String relativePath, String content) {
        clusterResourcesRepo.writeFile(relativePath, content)
    }

    void writeTenantBootstrapFile(String relativePath, String content) {
        tenantBootstrapRepoOrFail().writeFile(relativePath, content)
    }

    void commitAndPushClusterChanges(String message) {
        clusterResourcesRepo.commitAndPush(message)
    }

    void commitAndPushTenantBootstrapChanges(String message) {
        tenantBootstrapRepoOrFail().commitAndPush(message)
    }

    void commitAndPushAllChanges(String message) {
        commitAndPushClusterChanges(message)

        if (hasTenantBootstrapRepo()) {
            commitAndPushTenantBootstrapChanges(message)
        }
    }
}