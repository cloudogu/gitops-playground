package com.cloudogu.gitops.features.scm

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.scm.config.util.ScmProviderType
import com.cloudogu.gitops.git.GitProvider
import com.cloudogu.gitops.git.gitlab.Gitlab
import com.cloudogu.gitops.git.scmm.ScmManager
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import com.cloudogu.gitops.git.scmm.api.ScmmApiClient
@Slf4j
@Singleton
@Order(70)
class ScmHandler extends Feature {

    Config config

    //SCMM
    ScmmApiClient scmmApiClient
    HelmStrategy helmStrategy
    FileSystemUtils fileSystemUtils

    GitProvider tenant
    GitProvider central

    ScmHandler(Config config, ScmmApiClient scmmApiClient, HelmStrategy helmStrategy, FileSystemUtils fileSystemUtils) {
        this.config = config

        this.helmStrategy = helmStrategy
        this.scmmApiClient = scmmApiClient
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return true
    }

    //TODO Check settings
    void validate() {

    }

    void init() {
        validate()

        //TenantSCM
        switch (config.scm.scmProviderType) {
            case ScmProviderType.GITLAB:
                this.tenant = new Gitlab(this.config, this.config.scm.gitlabConfig)
                break
            case ScmProviderType.SCM_MANAGER:
                this.tenant = new ScmManager(this.config, config.scm.scmmConfig, scmmApiClient, this.helmStrategy, fileSystemUtils)
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
                this.central = new ScmManager(this.config, config.multiTenant.scmmConfig, scmmApiClient, this.helmStrategy, fileSystemUtils)
                break
            default:
                throw new IllegalArgumentException("Unsupported SCM-Central provider: ${config.scm.scmProviderType}")
        }
    }

}