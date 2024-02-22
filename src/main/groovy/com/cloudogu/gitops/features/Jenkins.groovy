package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(90)
class Jenkins extends Feature {

    private Map config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils

    Jenkins(
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
         NAME_PREFIX_ENVIRONMENT_VARS \
         REGISTRY_URL \
         REGISTRY_PATH
         */
        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/jenkins/init-jenkins.sh", [
                TRACE : config.application['trace'],
                JENKINS_HELM_CHART_VERSION: config.jenkins['helm']['version'],
                JENKINS_URL: config.jenkins['url'],
                JENKINS_USERNAME: config.jenkins['username'],
                JENKINS_PASSWORD: config.jenkins['password'],
                REMOTE_CLUSTER: config.application['remote'],
                BASE_URL: config.application['baseUrl'] ? config.application['baseUrl'] : '',
                // Those are needed for calls made to jenkinsCli and can be migrated to groovy easily
                K8S_VERSION: ApplicationConfigurator.K8S_VERSION,
                SCMM_URL:  config.scmm['url'],
                SCMM_PASSWORD: config.scmm['password'],
                JENKINS_METRICS_USERNAME: config.jenkins['metricsUsername'],
                JENKINS_METRICS_PASSWORD: config.jenkins['metricsPassword'],
                //REGISTRY_URL: config.registry['url'],
                //REGISTRY_PATH: config.registry['path'],
                REGISTRY_USERNAME: config.registry['username'],
                REGISTRY_PASSWORD: config.registry['password'],
                INSTALL_ARGOCD: config.features['argocd']['active'],
        ])
    }
}
