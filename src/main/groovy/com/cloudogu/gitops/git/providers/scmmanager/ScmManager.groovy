package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.features.git.config.util.ScmmConfig
import com.cloudogu.gitops.features.deployment.HelmStrategy

import com.cloudogu.gitops.git.providers.scmmanager.api.ScmmApiClient
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper

@Slf4j
class ScmManager implements GitProvider {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"

    String namespace = 'scm-manager'
    String releaseName = 'scm'
    Boolean internal
    HelmStrategy deployer
    ScmmApiClient scmmApiClient
    Config config
    FileSystemUtils fileSystemUtils
    ScmmConfig scmmConfig
    Credentials credentials
    String url // TODO:
    String scmmRepoTarget // TODO:

    ScmManager(Config config, ScmmConfig scmmConfig, ScmmApiClient scmmApiClient, HelmStrategy deployer, FileSystemUtils fileSystemUtils) {
        this.config = config
        this.namespace = namespace
        this.scmmApiClient = scmmApiClient
        this.deployer = deployer
        this.fileSystemUtils = fileSystemUtils
        this.scmmConfig = scmmConfig
        this.credentials= scmmConfig.credentials
    }



    static Map templateToMap(String filePath, Map parameters) {
        def hydratedString = new TemplatingEngine().template(new File(filePath), parameters)

        if (hydratedString.trim().isEmpty()) {
            // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
            return [:]
        }
        return new YamlSlurper().parseText(hydratedString) as Map
    }

    //TODO abklären, welche url ist (repoUrl)??
    @Override
    String getUrl() {
        if(this.scmmConfig.internal){
            return "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm/${this.scmmConfig.rootPath}"
        }
        return this.scmmConfig.url
    }

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        return false
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {

    }

    @Override
    String computePushUrl(String repoTarget) {
        return null
    }

    //TODO implement
    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {

    }

    //TODO implement
    @Override
    void deleteUser(String name) {

    }

    //TODO implement
    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }

    @Override
    String getProtocol() {
        return null
    }

    @Override
    String getHost() {
        return null
    }




    //TODO when git abctraction feature is ready, we will create before merge to main a branch, that
    // contain this code as preservation for oop
    /* =============================  SETUP FOR LATER ===========================================
void waitForScmmAvailable(int timeoutSeconds = 60, int intervalMillis = 2000) {
    long startTime = System.currentTimeMillis()
    long timeoutMillis = timeoutSeconds * 1000L

    while (System.currentTimeMillis() - startTime < timeoutMillis) {
        try {
            def call = this.scmmApiClient.generalApi().checkScmmAvailable()
            def response = call.execute()

            if (response.successful) {
                return
            } else {
                println "SCM-Manager not ready yet: HTTP ${response.code()}"
            }
        } catch (Exception e) {
            println "Waiting for SCM-Manager... Error: ${e.message}"
        }

        sleep(intervalMillis)
    }
    throw new RuntimeException("Timeout: SCM-Manager did not respond with 200 OK within ${timeoutSeconds} seconds")
}
  void setup(){
    setupInternalScm(this.namespace)
    setupHelm()
    installScmmPlugins()
    configureJenkinsPlugin()
}

void setupInternalScm(String namespace) {
    this.namespace = namespace
    setInternalUrl()
}

//TODO URL handling by object
String setInternalUrl() {
    this.url="http://scmm.${namespace}.svc.cluster.local/scm"
}

void setupHelm() {
    def templatedMap = templateToMap(HELM_VALUES_PATH, [
            host       : scmmConfig.ingress,
            remote     : config.application.remote,
            username   : this.scmmConfig.credentials.username,
            password   : this.scmmConfig.credentials.password,
            helm       : this.scmmConfig.helm,
            releaseName: releaseName
    ])

    def helmConfig = this.scmmConfig.helm
    def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
    def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

    this.deployer.deployFeature(
            helmConfig.repoURL,
            'scm-manager',
            helmConfig.chart,
            helmConfig.version,
            namespace,
            releaseName,
            tempValuesPath
    )
    waitForScmmAvailable()
}

//TODO System.env to config Object
def installScmmPlugins(Boolean restart = true) {

    if (System.getenv('SKIP_PLUGINS')?.toLowerCase() == 'true') {
        log.info("Skipping SCM plugin installation due to SKIP_PLUGINS=true")
        return
    }

    if (System.getenv('SKIP_RESTART')?.toLowerCase() == 'true') {
        log.info("Skipping SCMM restart due to SKIP_RESTART=true")
        restart = false
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
    def jenkinsUrl = System.getenv('JENKINS_URL_FOR_SCMM')
    if (jenkinsUrl) {
        pluginNames.add("scm-jenkins-plugin")
    }

    for (String pluginName : pluginNames) {
        log.info("Installing Plugin ${pluginName} ...")

        try {
            def response = scmmApiClient.pluginApi().install(pluginName, restart).execute()

            if (!response.isSuccessful()) {
                def message = "Installing Plugin '${pluginName}' failed with status: ${response.code()} - ${response.message()}"
                log.error(message)
                throw new RuntimeException(message)
            } else {
                log.info("Successfully installed plugin '${pluginName}'")
            }
        } catch (Exception e) {
            log.error("Installing Plugin '${pluginName}' failed with error: ${e.message}", e)
            throw new RuntimeException("Installing Plugin '${pluginName}' failed", e)
        }
    }
}

*/
}