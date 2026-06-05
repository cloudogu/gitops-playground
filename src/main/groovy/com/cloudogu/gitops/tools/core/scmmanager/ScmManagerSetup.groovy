package com.cloudogu.gitops.tools.core.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig
import com.cloudogu.gitops.infrastructure.deployment.HelmStrategy
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.ScmManager
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerUser
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

import java.nio.file.Path

@Slf4j
@Singleton
class ScmManagerSetup {

    static final String HELM_VALUES_PATH = 'argocd/cluster-resources/apps/scm-manager/templates/values.ftl.yaml'

    private final Config config
    private final ScmManagerConfig scmmConfig
    private final HelmStrategy helmStrategy
    private final ScmManager scmManager
    private final FileSystemUtils fileSystemUtils

    ScmManagerSetup(
            Config config,
            ScmManagerConfig scmmConfig,
            HelmStrategy helmStrategy,
            ScmManager scmManager,
            FileSystemUtils fileSystemUtils
    ) {
        this.config = config
        this.scmmConfig = scmmConfig
        this.helmStrategy = helmStrategy
        this.scmManager = scmManager
        this.fileSystemUtils = fileSystemUtils
    }

    void setupHelm() {
        String releaseName = 'scmm'

        def templatedMap = TemplatingEngine.templateToMap(
                HELM_VALUES_PATH,
                [
                        config     : config,
                        host       : scmmConfig.ingress,
                        username   : scmmConfig.credentials.username,
                        password   : scmmConfig.credentials.password,
                        helm       : scmmConfig.helm,
                        releaseName: releaseName
                ]
        )

        def helmConfig = scmmConfig.helm
        def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
        Path tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

        helmStrategy.deployFeature(
                helmConfig.repoURL,
                'scm-manager',
                helmConfig.chart,
                helmConfig.version,
                scmmConfig.namespace,
                releaseName,
                tempValuesPath
        )
    }

    void waitForScmmAvailable(int timeoutSeconds = 180, int intervalMillis = 5000, int startDelay = 0) {
        long startTime = System.currentTimeMillis()
        long timeoutMillis = timeoutSeconds * 1000L

        if (startDelay > 0) {
            sleep(startDelay)
        }

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                def call = scmManager.getApiClient().generalApi().checkScmmAvailable()
                def response = call.execute()

                if (response.successful) {
                    log.info('SCM-Manager is available.')
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

        if (config.jenkins.active) {
            configureJenkinsPlugin()
        }

        addDefaultUsers()

        log.info('SCM-Manager setup finished.')
    }

    private void installScmmPlugins() {
        if (config.scm.scmManager.skipPlugins) {
            log.debug('Skipping SCM-Manager plugin installation.')
            return
        }

        List<String> pluginNames = [
                'scm-mail-plugin',
                'scm-review-plugin',
                'scm-code-editor-plugin',
                'scm-editor-plugin',
                'scm-landingpage-plugin',
                'scm-el-plugin',
                'scm-readme-plugin',
                'scm-webhook-plugin',
                'scm-ci-plugin',
                'scm-metrics-prometheus-plugin'
        ]

        if (config.jenkins.active) {
            pluginNames.add('scm-jenkins-plugin')
        }

        boolean restartForThisPlugin = false

        pluginNames.each { String pluginName ->
            log.debug("Installing plugin ${pluginName} ...")

            restartForThisPlugin =
                    !config.scm.scmManager.skipRestart && pluginName == pluginNames.last()

            ScmManagerApiClient.handleApiResponse(
                    scmManager.getApiClient().pluginApi().install(pluginName, restartForThisPlugin)
            )
        }

        log.debug('SCM-Manager plugin installation finished successfully.')

        if (restartForThisPlugin) {
            waitForScmmAvailable(180, 2000, 100)
        }
    }

    private void setSetupConfigs() {
        def setupConfigs = [
                enableProxy             : false,
                proxyPort               : 8080,
                proxyServer             : 'proxy.mydomain.com',
                proxyUser               : null,
                proxyPassword           : null,
                realmDescription        : 'SONIA :: SCM Manager',
                disableGroupingGrid     : false,
                dateFormat              : 'YYYY-MM-DD HH:mm:ss',
                anonymousAccessEnabled  : false,
                anonymousMode           : 'OFF',
                baseUrl                 : scmManager.url,
                forceBaseUrl            : false,
                loginAttemptLimit       : -1,
                proxyExcludes           : [],
                skipFailedAuthenticators: false,
                pluginUrl               : 'https://plugin-center-api.scm-manager.org/api/v1/plugins/{version}?os={os}&arch={arch}',
                loginAttemptLimitTimeout: 300,
                enabledXsrfProtection   : true,
                namespaceStrategy       : 'CustomNamespaceStrategy',
                loginInfoUrl            : 'https://login-info.scm-manager.org/api/v1/login-info',
                releaseFeedUrl          : 'https://scm-manager.org/download/rss.xml',
                mailDomainName          : 'scm-manager.local',
                adminGroups             : [],
                adminUsers              : []
        ]

        ScmManagerApiClient.handleApiResponse(
                scmManager.getApiClient().generalApi().setConfig(setupConfigs)
        )

        log.debug('Successfully added SCM-Manager setup config.')
    }

    private void configureJenkinsPlugin() {
        def jenkinsPluginConfig = [
                disableRepositoryConfiguration: false,
                disableMercurialTrigger       : false,
                disableGitTrigger             : false,
                disableEventTrigger           : false,
                url                           : config.jenkins.urlForScm
        ] as Map<String, Object>

        ScmManagerApiClient.handleApiResponse(
                scmManager.getApiClient().pluginApi().configureJenkinsPlugin(jenkinsPluginConfig)
        )

        log.debug('Successfully configured Jenkins plugin in SCM-Manager.')
    }

    private void addDefaultUsers() {
        String metricsUsername = "${config.application.namePrefix}metrics"

        addUser(scmmConfig.gitOpsUsername, scmmConfig.password)
        addUser(metricsUsername, scmmConfig.password)
        grantUserPermissions(metricsUsername, ['metrics:read'])
    }

    private void addUser(String username, String password, String email = 'changeme@test.local') {
        ScmManagerUser userRequest = [
                name       : username,
                displayName: username,
                mail       : email,
                external   : false,
                password   : password,
                active     : true,
                _links     : [:]
        ]

        ScmManagerApiClient.handleApiResponse(
                scmManager.getApiClient().usersApi().addUser(userRequest)
        )

        log.debug("Successfully created SCM-Manager user ${username}.")
    }

    private void grantUserPermissions(String username, List<String> permissions) {
        def permissionBody = [permissions: permissions]

        ScmManagerApiClient.handleApiResponse(
                scmManager.getApiClient().usersApi().setPermissionForUser(username, permissionBody)
        )

        log.debug("Granted permissions ${permissions} to user ${username}.")
    }
}