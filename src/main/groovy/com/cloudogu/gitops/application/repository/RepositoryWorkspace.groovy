package com.cloudogu.gitops.application.repository

import com.cloudogu.gitops.infrastructure.git.GitRepo

import java.nio.file.Path

class RepositoryWorkspace {

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
        tenantBootstrapRepo != null
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

    String clusterRootDir() {
        clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()
    }

    String clusterAppsDir() {
        Path.of(clusterRootDir(), 'apps').toString()
    }

    String clusterAppDir(String toolName) {
        Path.of(clusterAppsDir(), toolName).toString()
    }

    String tenantRootDir() {
        tenantBootstrapRepoOrFail().getAbsoluteLocalRepoTmpDir()
    }

    String tenantAppsDir() {
        Path.of(tenantRootDir(), 'apps').toString()
    }

    String tenantAppDir(String toolName) {
        Path.of(tenantAppsDir(), toolName).toString()
    }

    String clusterResourcesRepoUrl() {
        "${clusterResourcesRepo.gitProvider.repoPrefix()}argocd/cluster-resources.git"
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