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

        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [

                GIT_COMMITTER_NAME           : config.application['gitName'],
                GIT_COMMITTER_EMAIL          : config.application['gitEmail'],
                GIT_AUTHOR_NAME              : config.application['gitName'],
                GIT_AUTHOR_EMAIL             : config.application['gitEmail'],
                TRACE                        : config.application['trace'],
                SCMM_URL                     : config.scmm['url'],
                SCMM_USERNAME                : config.scmm['username'],
                SCMM_PASSWORD                : config.scmm['password'],
                JENKINS_URL                  : config.jenkins['url'],
                INTERNAL_SCMM                : config.scmm['internal'],
                JENKINS_URL_FOR_SCMM         : config.jenkins['urlForScmm'],
                SCMM_URL_FOR_JENKINS         : config.scmm['urlForJenkins'],
                REMOTE_CLUSTER               : config.application['remote'],
                BASE_URL                     : config.application['baseUrl'] ? config.application['baseUrl'] : '',
                INSTALL_ARGOCD               : config.features['argocd']['active'],
                SCMM_HELM_CHART_VERSION      : config.scmm['helm']['version'],
                SPRING_BOOT_HELM_CHART_COMMIT: config.repositories['springBootHelmChart']['ref'],
                SPRING_BOOT_HELM_CHART_REPO  : config.repositories['springBootHelmChart']['url'],
                GITOPS_BUILD_LIB_REPO        : config.repositories['gitopsBuildLib']['url'],
                CES_BUILD_LIB_REPO           : config.repositories['cesBuildLib']['url'],
                NAME_PREFIX                  : config.application['namePrefix'],
                INSECURE                     : config.application['insecure']
        ])
    }
}
