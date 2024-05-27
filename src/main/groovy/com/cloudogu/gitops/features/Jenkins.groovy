package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JobManager
import com.cloudogu.gitops.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.jenkins.UserManager
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
    private GlobalPropertyManager globalPropertyManager
    private JobManager jobManger
    private UserManager userManager
    private PrometheusConfigurator prometheusConfigurator

    Jenkins(
            Configuration config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            GlobalPropertyManager globalPropertyManager,
            JobManager jobManger,
            UserManager userManager,
            PrometheusConfigurator prometheusConfigurator
    ) {
        this.config = config.getConfig()
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.globalPropertyManager = globalPropertyManager
        this.jobManger = jobManger
        this.userManager = userManager
        this.prometheusConfigurator = prometheusConfigurator
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {
        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/jenkins/init-jenkins.sh", [
                TRACE                     : config.application['trace'],
                INTERNAL_JENKINS          : config.jenkins['internal'],
                JENKINS_HELM_CHART_VERSION: config.jenkins['helm']['version'],
                JENKINS_URL               : config.jenkins['url'],
                JENKINS_USERNAME          : config.jenkins['username'],
                JENKINS_PASSWORD          : config.jenkins['password'],
                REMOTE_CLUSTER            : config.application['remote'],
                BASE_URL                  : config.application['baseUrl'] ? config.application['baseUrl'] : '',
                SCMM_URL                  : config.scmm['urlForJenkins'],
                SCMM_PASSWORD             : config.scmm['password'],
                INSTALL_ARGOCD            : config.features['argocd']['active'],
                NAME_PREFIX               : config.application['namePrefix'],
                INSECURE                  : config.application['insecure'],
                URL_SEPARATOR_HYPHEN      : config.application['urlSeparatorHyphen']
        ])

        globalPropertyManager.setGlobalProperty('SCMM_URL', config.scmm['url'] as String)
        if (config.registry['twoRegistries']) {
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}REGISTRY_PULL_URL", config.registry['pullUrl'] as String)
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}REGISTRY_PULL_PATH", config.registry['pullPath'] as String)
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}REGISTRY_PUSH_URL", config.registry['pushUrl'] as String)
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}REGISTRY_PUSH_PATH", config.registry['pushPath'] as String)
        } else {
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}REGISTRY_URL", config.registry['url'] as String)
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}REGISTRY_PATH", config.registry['path'] as String)
        }

        if (config.jenkins['mavenCentralMirror']) {
            globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}MAVEN_CENTRAL_MIRROR", config.jenkins['mavenCentralMirror'] as String)
        }

        globalPropertyManager.setGlobalProperty("${config.application['namePrefixForEnvVars']}K8S_VERSION", ApplicationConfigurator.K8S_VERSION)

        if (userManager.isUsingCasSecurityRealm()) {
            log.trace("Using CAS Security Realm. Must not create user.")
        } else {
            userManager.createUser(config.jenkins['metricsUsername'] as String, config.jenkins['metricsPassword'] as String)
        }
        userManager.grantPermission(config.jenkins['metricsUsername'] as String, UserManager.Permissions.METRICS_VIEW)

        prometheusConfigurator.enableAuthentication()

        if (config.features['argocd']['active']) {
            jobManger.createCredential(
                    "${config.application['namePrefix']}example-apps",
                    "scmm-user",
                    "${config.application['namePrefix']}gitops",
                    "${config.scmm['password']}",
                    'credentials for accessing scm-manager')

            if (config.registry['twoRegistries']) {
                jobManger.createCredential(
                        "${config.application['namePrefix']}example-apps",
                        "registry-pull-user",
                        "${config.registry['pullUsername']}",
                        "${config.registry['pullPassword']}",
                        'credentials for accessing the docker-registry that contains 3rd party or base images')
                jobManger.createCredential(
                        "${config.application['namePrefix']}example-apps",
                        "registry-push-user",
                        "${config.registry['pushUsername']}",
                        "${config.registry['pushPassword']}",
                        'credentials for accessing the docker-registry that contains images built on jenkins')
            } else {
                jobManger.createCredential(
                        "${config.application['namePrefix']}example-apps",
                        "registry-user",
                        "${config.registry['username']}",
                        "${config.registry['password']}",
                        'credentials for accessing the docker-registry')
            }
        }
    }
}
