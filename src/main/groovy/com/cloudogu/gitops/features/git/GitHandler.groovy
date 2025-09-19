package com.cloudogu.gitops.features.git

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmProviderType
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.gitlab.GitlabProvider
import com.cloudogu.gitops.git.providers.scmmanager.ScmManagerProvider
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(70)
class GitHandler extends Feature {

//    Config config
//
//    //SCMM
//    ScmManagerApiClient scmmApiClient
//    HelmStrategy helmStrategy
//    FileSystemUtils fileSystemUtils
//
//    GitProvider tenant
//    GitProvider central
//
//    GitHandler(Config config, ScmManagerApiClient scmmApiClient, HelmStrategy helmStrategy, FileSystemUtils fileSystemUtils) {
//        this.config = config
//
//        this.helmStrategy = helmStrategy
//        this.scmmApiClient = scmmApiClient
//        this.fileSystemUtils = fileSystemUtils
//    }
//
    @Override
    boolean isEnabled() {
        return true
    }
//
//    //TODO Check settings
//    void validate() {
//
//    }
//
//
////    GitProvider getResourcesScm() {
////        central ?: tenant ?: { throw new IllegalStateException("No SCM provider found.") }()
////    }
//
//    void init() {
//        validate()
//
//        //TenantSCM
//        switch (config.scm.scmProviderType) {
//            case ScmProviderType.GITLAB:
//                this.tenant = new GitlabProvider(this.config, this.config.scm.gitlabConfig)
//                break
//            case ScmProviderType.SCM_MANAGER:
//                this.tenant = new ScmManagerProvider(this.config, config.scm.scmmConfig, scmmApiClient, this.helmStrategy, fileSystemUtils)
//                // this.tenant.setup() setup will be here in future
//                break
//            default:
//                throw new IllegalArgumentException("Unsupported SCM provider found in TenantSCM")
//        }
//
//        //CentralSCM
//        switch (config.multiTenant.scmProviderType) {
//            case ScmProviderType.GITLAB:
//                this.central = new GitlabProvider(this.config, this.config.multiTenant.gitlabConfig)
//                break
//            case ScmProviderType.SCM_MANAGER:
//                this.central = new ScmManagerProvider(this.config, config.multiTenant.scmmConfig, scmmApiClient, this.helmStrategy, fileSystemUtils)
//                break
//            default:
//                throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.scm.scmProviderType}")
//        }
//
//        //can be removed if we combine argocd and cluster-resources
//        if(this.central){
//            setupRepos(this.central)
//            this.tenant.createRepository("argocd/argocd","GitOps repo for administration of ArgoCD")
//            create3thPartyDependecies(this.central)
//        }else{
//            setupRepos(this.tenant)
//            create3thPartyDependecies(this.tenant)
//        }
//        createExampleApps(this.tenant)
//        createExercises(this.tenant)
//    }
//
//    static void setupRepos(GitProvider gitProvider){
//        gitProvider.createRepository("argocd/argocd","GitOps repo for administration of ArgoCD")
//        gitProvider.createRepository("argocd/cluster-resources","GitOps repo for basic cluster-resources")
//    }
//
//    static createExampleApps(GitProvider gitProvider){
//        gitProvider.createRepository("argocd/nginx-helm-jenkins","3rd Party app (NGINX) with helm, templated in Jenkins (gitops-build-lib)")
//        gitProvider.createRepository("argocd/petclinic-plain","Java app with plain k8s resources")
//        gitProvider.createRepository("argocd/petclinic-helm","Java app with custom helm chart")
//        gitProvider.createRepository("argocd/example-apps","GitOps repo for examples of end-user applications")
//    }
//
//    static create3thPartyDependecies(GitProvider gitProvider){
//        gitProvider.createRepository("3rd-party-dependencies/spring-boot-helm-chart","spring-boot-helm-chart")
//        gitProvider.createRepository("3rd-party-dependencies/spring-boot-helm-chart-with-dependency","spring-boot-helm-chart-with-dependency")
//        gitProvider.createRepository("3rd-party-dependencies/gitops-build-lib","Jenkins pipeline shared library for automating deployments via GitOps")
//        gitProvider.createRepository("3rd-party-dependencies/ces-build-lib","Jenkins pipeline shared library adding features for Maven, Gradle, Docker, SonarQube, Git and others")
//    }
//
//    static createExercises(GitProvider gitProvider){
//        gitProvider.createRepository("exercises/petclinic-helm","3rd Party app (NGINX) with helm, templated in Jenkins (gitops-build-lib)")
//        gitProvider.createRepository("exercises/nginx-validation","Java app with plain k8s resources")
//        gitProvider.createRepository("exercises/broken-application","Java app with custom helm chart")
//    }
}