package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature

import com.cloudogu.gitops.config.Config
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
@Order(70)
class Jenkins extends Feature {

    private Config config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private GlobalPropertyManager globalPropertyManager
    private JobManager jobManger
    private UserManager userManager
    private PrometheusConfigurator prometheusConfigurator

    Jenkins(
            Config config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            GlobalPropertyManager globalPropertyManager,
            JobManager jobManger,
            UserManager userManager,
            PrometheusConfigurator prometheusConfigurator
    ) {
        this.config = config
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
                TRACE                     : config.application.trace,
                INTERNAL_JENKINS          : config.jenkins.internal,
                JENKINS_HELM_CHART_VERSION: config.jenkins.helm.version,
                JENKINS_URL               : config.jenkins.url,
                JENKINS_USERNAME          : config.jenkins.username,
                JENKINS_PASSWORD          : config.jenkins.password,
                REMOTE_CLUSTER            : config.application.remote,
                BASE_URL                  : config.application.baseUrl ? config.application.baseUrl : '',
                SCMM_URL                  : config.scmm.urlForJenkins,
                SCMM_PASSWORD             : config.scmm.password,
                INSTALL_ARGOCD            : config.features.argocd.active,
                NAME_PREFIX               : config.application.namePrefix,
                INSECURE                  : config.application.insecure,
                URL_SEPARATOR_HYPHEN      : config.application.urlSeparatorHyphen
        ])

        globalPropertyManager.setGlobalProperty('SCMM_URL', config.scmm.url)

        if (config.jenkins.additionalEnvs) {
            for (entry in (config.jenkins.additionalEnvs as Map).entrySet()) {
                globalPropertyManager.setGlobalProperty(entry.key.toString(), entry.value.toString())
            }
        }

        if (config.registry.url) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_URL", config.registry.url)
        }

        if (config.registry.path) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PATH", config.registry.path)
        }

        if (config.registry.twoRegistries) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PROXY_URL", config.registry.proxyUrl)
        }

        if (config.jenkins.mavenCentralMirror) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}MAVEN_CENTRAL_MIRROR", config.jenkins.mavenCentralMirror)
        }

        globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}K8S_VERSION", Config.K8S_VERSION)

        if (userManager.isUsingCasSecurityRealm()) {
            log.trace("Using CAS Security Realm. Must not create user.")
        } else {
            userManager.createUser(config.jenkins.metricsUsername, config.jenkins.metricsPassword)
        }

        userManager.grantPermission(config.jenkins.metricsUsername, UserManager.Permissions.METRICS_VIEW)

        prometheusConfigurator.enableAuthentication()

        if (config.features.argocd.active) {

            String jobName = "${config.application.namePrefix}example-apps"
            def credentialId = "scmm-user"

            jobManger.createJob(jobName,
                    config.scmm.urlForJenkins,
                    "${config.application.namePrefix}argocd",
                    credentialId)

            jobManger.createCredential(
                    jobName,
                    credentialId,
                    "${config.application.namePrefix}gitops",
                    "${config.scmm.password}",
                    'credentials for accessing scm-manager')

            jobManger.createCredential(
                    jobName,
                    "registry-user",
                    "${config.registry.username}",
                    "${config.registry.password}",
                    'credentials for accessing the docker-registry for writing images built on jenkins')

            if (config.registry.twoRegistries) {
                jobManger.createCredential(
                        "${config.application.namePrefix}example-apps",
                        "registry-proxy-user",
                        "${config.registry.proxyUsername}",
                        "${config.registry.proxyPassword}",
                        'credentials for accessing the docker-registry that contains 3rd party or base images')

            }
            // Once everything is set up, start the jobs.
            jobManger.startJob(jobName)
        }
    }
}