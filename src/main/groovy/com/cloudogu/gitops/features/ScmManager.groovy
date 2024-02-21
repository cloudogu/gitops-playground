package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(80)
class ScmManager extends Feature {

    private Map config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils

    ScmManager(
            Configuration config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils
    ) {
        this.config = config.getConfig()
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {

        /* Values set in apply.sh:
            INTERNAL_SCMM \
         INTERNAL_JENKINS \
         JENKINS_URL_FOR_SCMM \
         SCMM_URL_FOR_JENKINS \
         SCMM_URL \
         JENKINS_URL \
         NAME_PREFIX \
         NAME_PREFIX_ENVIRONMENT_VARS
         */
        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [
                TRACE : config.application['trace'],
                ABSOLUTE_BASEDIR:  new File(System.getProperty('user.dir'), 'scripts'),
                PLAYGROUND_DIR: System.getProperty('user.dir'),
                SCMM_URL:  config.scmm['url'],
                SCMM_USERNAME: config.scmm['username'],
                SCMM_PASSWORD: config.scmm['password'],
                REMOTE_CLUSTER: config.application['remote'],
                BASE_URL: config.application['baseUrl'] ? config.application['baseUrl'] : '',
                INSTALL_ARGOCD: config.features['argocd']['active'],
                SCMM_HELM_CHART_VERSION: config.scmm['helm']['version'],
                SPRING_BOOT_HELM_CHART_COMMIT: config.repositories['springBootHelmChart']['ref'],
                SPRING_BOOT_HELM_CHART_REPO: config.repositories['springBootHelmChart']['url'],
                GITOPS_BUILD_LIB_REPO: config.repositories['gitopsBuildLib']['url'],
                CES_BUILD_LIB_REPO: config.repositories['cesBuildLib']['url']
        ])
    }
}
