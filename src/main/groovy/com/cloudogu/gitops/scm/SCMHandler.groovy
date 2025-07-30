package com.cloudogu.gitops.scm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.ScmManager
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(200)
class SCMHandler {

    ISCM scm
    ISCM centralSCM
    Config config

    SCMHandler(Config config) {
        this.config = config
        if (config.multiTenant.useDedicatedInstance) {
            centralSCM = createSCM(config.multiTenant.provider)
        }

        this.scm = createSCM(config.scmm.provider)
    }

    private ISCM createSCM(Config.ScmProviderType provider) {
        switch (provider) {
            case Config.ScmProviderType.GITLAB:
                return new Gitlab(this.config)
            case Config.ScmProviderType.SCM_MANAGER:
                return new ScmManager()
            default:
                throw new IllegalArgumentException("Unsupported SCMHandler provider: $provider")
        }
    }
}