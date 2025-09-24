package com.cloudogu.gitops.features.git

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.gitlab.Gitlab
import com.cloudogu.gitops.git.providers.scmmanager.ScmManager
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient

@Slf4j
@Singleton
@Order(70)
class GitHandler extends Feature {

    Config config

    HelmStrategy helmStrategy
    FileSystemUtils fileSystemUtils

    GitProvider tenant
    GitProvider central

    GitHandler(Config config, HelmStrategy helmStrategy, FileSystemUtils fileSystemUtils) {
        this.config = config
        this.helmStrategy = helmStrategy
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return true
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

    void init() {
        validate()

        //TenantSCM
        switch (config.scm.scmProviderType) {
            case ScmProviderType.GITLAB:
                this.tenant = new Gitlab(this.config, this.config.scm.gitlabConfig)
                break
            case ScmProviderType.SCM_MANAGER:
                this.tenant = new ScmManager(this.config, config.scm.scmmConfig)
                // this.tenant.setup() setup will be here in future
                break
            default:
                throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM")
        }

        //CentralSCM
        switch (config.multiTenant.scmProviderType) {
            case ScmProviderType.GITLAB:
                this.central = new Gitlab(this.config, this.config.multiTenant.gitlabConfig)
                break
            case ScmProviderType.SCM_MANAGER:
                this.central = new ScmManager(this.config, config.multiTenant.scmmConfig)
                break
            default:
                throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.scm.scmProviderType}")
        }

        //can be removed if we combine argocd and cluster-resources
        if (this.central) {
            setupRepos(this.central)
            this.tenant.createRepository("argocd/argocd","GitOps repo for administration of ArgoCD")
            create3thPartyDependecies(this.central)
        } else {
            setupRepos(this.tenant)
            create3thPartyDependecies(this.tenant)
        }
    }

    static void setupRepos(GitProvider gitProvider) {
        gitProvider.createRepository("argocd/argocd","GitOps repo for administration of ArgoCD")
        gitProvider.createRepository("argocd/cluster-resources","GitOps repo for basic cluster-resources")
    }

    static create3thPartyDependecies(GitProvider gitProvider) {
        gitProvider.createRepository("3rd-party-dependencies/spring-boot-helm-chart","spring-boot-helm-chart")
        gitProvider.createRepository("3rd-party-dependencies/spring-boot-helm-chart-with-dependency","spring-boot-helm-chart-with-dependency")
        gitProvider.createRepository("3rd-party-dependencies/gitops-build-lib","Jenkins pipeline shared library for automating deployments via GitOps")
        gitProvider.createRepository("3rd-party-dependencies/ces-build-lib","Jenkins pipeline shared library adding features for Maven, Gradle, Docker, SonarQube, Git and others")
    }

}