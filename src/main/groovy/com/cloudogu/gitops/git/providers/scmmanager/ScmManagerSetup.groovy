package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerUser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import groovy.util.logging.Slf4j

@Slf4j
class ScmManagerSetup {

    private ScmManager scmManager

    static final String HELM_VALUES_PATH = "argocd/cluster-resources/apps/scm-manager/templates/values.ftl.yaml"

    ScmManagerSetup(ScmManager scmManager) {
        this.scmManager = scmManager
    }

    void waitForScmmAvailable(int timeoutSeconds = 180, int intervalMillis = 5000, int startDelay = 0) {
        long startTime = System.currentTimeMillis()
        long timeoutMillis = timeoutSeconds * 1000L
        sleep(startDelay)
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                def call = scmManager.apiClient.generalApi().checkScmmAvailable()
                def response = call.execute()

                if (response.successful) {
                    return
                }
            } catch (Exception e) {
               log.debug("Waiting for SCM-Manager... Error: ${e.message}")
            }


            sleep(intervalMillis)
        }
        throw new RuntimeException("Timeout: SCM-Manager did not respond with 200 OK within ${timeoutSeconds} seconds")
    }

    void configure() {
        installScmmPlugins()
        setSetupConfigs()
        if (this.scmManager.config.jenkins.active) {
            configureJenkinsPlugin()
        }
        addDefaultUsers()
        log.info("ScmManager Setup finished!")
    }

    void setupHelm() {
        def releaseName = 'scmm'

        def templatedMap = TemplatingEngine.templateToMap(HELM_VALUES_PATH, [
                host       : this.scmManager.scmmConfig.ingress,
                username   : this.scmManager.scmmConfig.credentials.username,
                password   : this.scmManager.scmmConfig.credentials.password,
                helm       : this.scmManager.scmmConfig.helm,
                releaseName: releaseName
        ])

        def helmConfig = this.scmManager.scmmConfig.helm
        def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
        def tempValuesPath = new FileSystemUtils().writeTempFile(mergedMap)
        this.scmManager.helmStrategy.deployFeature(
                helmConfig.repoURL,
                'scm-manager',
                helmConfig.chart,
                helmConfig.version,
                this.scmManager.scmmConfig.namespace,
                releaseName,
                tempValuesPath
        )
    }

    def installScmmPlugins() {

        if (this.scmManager.config.scm.scmManager.skipPlugins) {
            log.debug("Skipping SCM plugin installation")
            return
        }

        def pluginNames = [
                "scm-mail-plugin",
                "scm-review-plugin",
                "scm-code-editor-plugin",
                "scm-editor-plugin",
                "scm-landingpage-plugin",
                "scm-el-plugin",
                "scm-readme-plugin",
                "scm-webhook-plugin",
                "scm-ci-plugin",
                "scm-metrics-prometheus-plugin"
        ]

        if (this.scmManager.config.jenkins.active) {
            pluginNames.add("scm-jenkins-plugin")
        }
        Boolean restartForThisPlugin = false
        pluginNames.each { String pluginName ->
            log.debug("Installing Plugin ${pluginName} ...")
            restartForThisPlugin = !this.scmManager.config.scm.scmManager.skipRestart && pluginName == pluginNames.last()
            ScmManagerApiClient.handleApiResponse(scmManager.apiClient.pluginApi().install(pluginName, restartForThisPlugin))
        }

        log.debug("SCM-Manager plugin installation finished successfully!")
        if (restartForThisPlugin) {
            waitForScmmAvailable(180,2000,100)
        }
    }

    void setSetupConfigs() {
        def setupConfigs = [
                enableProxy             : false,
                proxyPort               : 8080,
                proxyServer             : "proxy.mydomain.com",
                proxyUser               : null,
                proxyPassword           : null,
                realmDescription        : "SONIA :: SCM Manager",
                disableGroupingGrid     : false,
                dateFormat              : "YYYY-MM-DD HH:mm:ss",
                anonymousAccessEnabled  : false,
                anonymousMode           : "OFF",
                baseUrl                 : this.scmManager.url,
                forceBaseUrl            : false,
                loginAttemptLimit       : -1,
                proxyExcludes           : [],
                skipFailedAuthenticators: false,
                pluginUrl               : "https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}",
                loginAttemptLimitTimeout: 300,
                enabledXsrfProtection   : true,
                namespaceStrategy       : "CustomNamespaceStrategy",
                loginInfoUrl            : "https://login-info.scm-manager.org/api/v1/login-info",
                releaseFeedUrl          : "https://scm-manager.org/download/rss.xml",
                mailDomainName          : "scm-manager.local",
                adminGroups             : [],
                adminUsers              : []
        ]

        ScmManagerApiClient.handleApiResponse(scmManager.apiClient.generalApi().setConfig(setupConfigs))
        log.debug("Successfully added SCMM Setup Configs")
    }

    void configureJenkinsPlugin() {

        def jenkinsPluginConfig = [
                disableRepositoryConfiguration: false,
                disableMercurialTrigger       : false,
                disableGitTrigger             : false,
                disableEventTrigger           : false,
                url                           : this.scmManager.config.jenkins.urlForScm
        ] as Map<String, Object>

        ScmManagerApiClient.handleApiResponse(this.scmManager.apiClient.pluginApi().configureJenkinsPlugin(jenkinsPluginConfig))
        log.debug("Successfully configured JenkinsPlugin in SCM-Manager.")
    }

    void addDefaultUsers() {
        def metricsUsername = "${this.scmManager.config.application.namePrefix}metrics"
        addUser(this.scmManager.scmmConfig.gitOpsUsername, this.scmManager.scmmConfig.password)
        addUser(metricsUsername, this.scmManager.scmmConfig.password)
        grantUserPermissions(metricsUsername, ["metrics:read"])
    }

    void addUser(String username, String password, String email = 'changeme@test.local') {
        ScmManagerUser userRequest = [
                name       : username,
                displayName: username,
                mail       : email,
                external   : false,
                password   : password,
                active     : true,
                _links     : [:]
        ]
        ScmManagerApiClient.handleApiResponse(scmManager.apiClient.usersApi().addUser(userRequest))
        log.debug("Successfully created SCM-Manager User.")
    }

    void grantUserPermissions(String username, List<String> permissions) {
        def permissionBody = [
                permissions: permissions
        ]
        ScmManagerApiClient.handleApiResponse(scmManager.apiClient.usersApi().setPermissionForUser(username, permissionBody))
        log.debug("Granted permissions ${permissions} to user ${username}.")
    }
}