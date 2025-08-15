package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.ScmSchema
import com.cloudogu.gitops.scm.Gitlab
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scm.scmm.ScmManager
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(200)
class MultiTenant {

    Config config

    ISCM tenant
    ISCM central

    MultiTenant(Config config) {
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


    }
}