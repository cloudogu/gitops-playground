package com.cloudogu.gitops.features.git

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.gitlab.Gitlab
import com.cloudogu.gitops.git.providers.scmmanager.ScmManager
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(70)
class GitHandler extends Feature {

    Config config

    NetworkingUtils networkingUtils
    HelmStrategy helmStrategy
    FileSystemUtils fileSystemUtils
    K8sClient k8sClient

    GitProvider tenant
    GitProvider central


    GitHandler(Config config, HelmStrategy helmStrategy, FileSystemUtils fileSystemUtils, K8sClient k8sClient,NetworkingUtils networkingUtils) {
        this.config = config
        this.helmStrategy = helmStrategy
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.networkingUtils=networkingUtils
    }

    @Override
    boolean isEnabled() {
        return true
    }

    //TODO configure  settings
    void configure(){


    }
    //TODO Check settings
    void validate() {

    }

    //Retrieves the appropriate SCM for cluster resources depending on whether the environment is multi-tenant or not.
    GitProvider getResourcesScm() {
        if (central) {
            return central
        } else if (tenant) {
            return tenant
        } else {
            throw new IllegalStateException("No SCM provider found.")
        }
    }

    @Override
    void enable() {

        validate()
        configure()
        //TenantSCM
        switch (config.scm.scmProviderType) {
            case ScmProviderType.GITLAB:
                this.tenant = new Gitlab(this.config, this.config.scm.gitlab)
                break
            case ScmProviderType.SCM_MANAGER:
                def prefixedNamespace = "${config.application.namePrefix}scm-manager".toString()
                config.scm.scmManager.namespace = prefixedNamespace
                this.tenant = new ScmManager(this.config, config.scm.scmManager,k8sClient,networkingUtils)
                // this.tenant.setup() setup will be here in future
                break
            default:
                throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM")
        }

        if (config.multiTenant.useDedicatedInstance) {
            switch (config.multiTenant.scmProviderType) {
                case ScmProviderType.GITLAB:
                    this.central = new Gitlab(this.config, this.config.multiTenant.gitlabConfig)
                    break
                case ScmProviderType.SCM_MANAGER:
                    this.central = new ScmManager(this.config, config.multiTenant.scmManager,k8sClient,networkingUtils)
                    break
                default:
                    throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.scm.scmProviderType}")
        }}

        //can be removed if we combine argocd and cluster-resources
        final String namePrefix = (config?.application?.namePrefix ?: "").trim()
        if (this.central) {
            setupRepos(this.central, namePrefix)
            setupRepos(this.tenant, namePrefix, false)
        } else {
            setupRepos(this.tenant, namePrefix, true)
        }
        create3thPartyDependencies(this.tenant, namePrefix)
    }

    // includeClusterResources = true => also create the argocd/cluster-resources repository
    static void setupRepos(GitProvider gitProvider, String namePrefix = "", boolean includeClusterResources = true) {
        gitProvider.createRepository(
                withOrgPrefix(namePrefix, "argocd/argocd"),
                "GitOps repo for administration of ArgoCD"
        )
        if (includeClusterResources) {
            gitProvider.createRepository(
                    withOrgPrefix(namePrefix, "argocd/cluster-resources"),
                    "GitOps repo for basic cluster-resources"
            )
        }
    }

    static create3thPartyDependencies(GitProvider gitProvider, String namePrefix = "") {
        gitProvider.createRepository(withOrgPrefix(namePrefix,"3rd-party-dependencies/spring-boot-helm-chart"), "spring-boot-helm-chart")
        gitProvider.createRepository(withOrgPrefix(namePrefix,"3rd-party-dependencies/spring-boot-helm-chart-with-dependency"), "spring-boot-helm-chart-with-dependency")
        gitProvider.createRepository(withOrgPrefix(namePrefix,"3rd-party-dependencies/gitops-build-lib"), "Jenkins pipeline shared library for automating deployments via GitOps")
        gitProvider.createRepository(withOrgPrefix(namePrefix,"3rd-party-dependencies/ces-build-lib"), "Jenkins pipeline shared library adding features for Maven, Gradle, Docker, SonarQube, Git and others")
    }


    /**
     * Adds a prefix to the ORG part (before the first '/'):
     * Example: "argocd/argocd" + "foo-" => "foo-argocd/argocd"
     */
    static String withOrgPrefix(String prefix, String repoPath) {
        if (!prefix) return repoPath
        return prefix + repoPath
    }
}