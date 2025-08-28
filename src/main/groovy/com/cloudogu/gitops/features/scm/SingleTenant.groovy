package com.cloudogu.gitops.features.scm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.scm.Gitlab
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scm.ScmManager
import com.cloudogu.gitops.scm.config.util.ScmProviderType
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(60)
class SingleTenant {

    Config config

    //SCMM
    ScmmApiClient scmmApiClient
    HelmStrategy helmStrategy
    FileSystemUtils fileSystemUtils


    ISCM scm

    SingleTenant(Config config, ScmmApiClient scmmApiClient, HelmStrategy helmStrategy, FileSystemUtils fileSystemUtils) {
        this.config = config

        this.helmStrategy = helmStrategy
        this.scmmApiClient = scmmApiClient
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return !config.multiTenant.useDedicatedInstance
    }

    //TODO Check settings
    void validate() {

    }

    void init() {
        validate()

        switch (config.scm.scmProviderType) {
            case ScmProviderType.GITLAB:
                this.scm = new Gitlab(this.config, null)
                break
            case ScmProviderType.SCM_MANAGER:
                this.scm = new ScmManager(this.config, config.scm.scmmConfig, scmmApiClient, this.helmStrategy, fileSystemUtils)
                break
            default:
                throw new IllegalArgumentException("Unsupported SCM provider: ${config.scm.scmProviderType}")
        }

    }

}