package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.ScmSchema
import com.cloudogu.gitops.features.argocd.RepoInitializationAction
import com.cloudogu.gitops.scm.gitlab.Gitlab
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scm.scmm.ScmManager
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(200)
class MultiTenant {

    Config config
    ScmmRepoProvider repoProvider


    ISCM tenant
    ISCM central

    MultiTenant(Config config,ScmmRepoProvider repoProvider) {
        this.repoProvider=repoProvider
        this.config = config
    }

    @Override
    boolean isEnabled() {
        return config.multiTenant.useDedicatedInstance
    }

   init(){
       //TenantSCM
        switch(config.scm.provider) {
            case ScmSchema.ScmProviderType.GITLAB:
                this.tenant = new Gitlab()
                break
            case ScmSchema.ScmProviderType.SCM_MANAGER:
                this.tenant = new ScmManager()
                break
            default:
                throw new IllegalArgumentException("Unsupported SCM provider: ${config.scm.provider}")
        }
       this.tenant.setup()

       //CentralSCM
       switch(config.multiTenant.centalScmProviderType) {
           case ScmSchema.ScmProviderType.GITLAB:
               this.central = new Gitlab()
               break
           case ScmSchema.ScmProviderType.SCM_MANAGER:
               this.central = new ScmManager()
               break
           default:
               throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.scm.provider}")
       }
       this.central.init()

    }

    setupTenant(){
        new RepoInitializationAction(config, repoProvider.getRepo('argocd/arcocd'), localSrcDir)
    }
}