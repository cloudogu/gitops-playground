package com.cloudogu.gitops.scm.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.config.ScmmSchema
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import com.cloudogu.gitops.scmm.jgit.InsecureCredentialProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import retrofit2.Response

@Slf4j
class ScmManager implements ISCM {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"

    String namespace = ''
    String releaseName = 'scm'
    Boolean internal
    HelmStrategy deployer
    ScmmApiClient scmmApiClient
    Config config
    FileSystemUtils fileSystemUtils
    ScmmSchema scmmConfig

    Credentials credentials

    ScmManager(Config config, ScmmSchema scmmConfig, ScmmApiClient scmmApiClient, HelmStrategy deployer, FileSystemUtils fileSystemUtils) {
        this.config = config
        this.scmmApiClient = scmmApiClient
        this.deployer = deployer
        this.fileSystemUtils = fileSystemUtils
        this.scmmConfig = scmmConfig
        this.credentials = new Credentials(scmmConfig.username, scmmConfig.password)
    }

    void setupHelm() {
        def templatedMap = templateToMap(HELM_VALUES_PATH, [
                host       : scmmConfig.ingress,
                remote     : config.application.remote,
                username   : scmmConfig.username,
                password   : scmmConfig.password,
                helm       : scmmConfig.helm,
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

    String getInternalUrl() {
        return "http://scmm.${namespace}.svc.cluster.local/scm"
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

    /**
     * @return true if created, false if already exists. Throw exception on all other errors
     */
    boolean create(String description, ScmmApiClient scmmApiClient) {
        def namespace = scmmRepoTarget.split('/', 2)[0]
        def repoName = scmmRepoTarget.split('/', 2)[1]

        def repositoryApi = scmmApiClient.repositoryApi()
        def repo = new Repository(namespace, repoName, description)
        log.debug("Creating repo: ${namespace}/${repoName}")
        def createResponse = repositoryApi.create(repo, true).execute()
        handleResponse(createResponse, repo)

        def permission = new Permission(config.scmm.gitOpsUsername as String, Permission.Role.WRITE)
        def permissionResponse = repositoryApi.createPermission(namespace, repoName, permission).execute()
        return handleResponse(permissionResponse, permission, "for repo $namespace/$repoName")
    }

    private static boolean handleResponse(Response<Void> response, Object body, String additionalMessage = '') {
        if (response.code() == 409) {
            // Here, we could consider sending another request for changing the existing object to become proper idempotent
            log.debug("${body.class.simpleName} already exists ${additionalMessage}, ignoring: ${body}")
            return false // because repo exists
        } else if (response.code() != 201) {
            throw new RuntimeException("Could not create ${body.class.simpleName} ${additionalMessage}.\n${body}\n" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
        return true// because its created
    }

    public void configureJenkinsPlugin() {
        def config = [
                disableRepositoryConfiguration: false,
                disableMercurialTrigger       : false,
                disableGitTrigger             : false,
                disableEventTrigger           : false,
                url                           : jenkinsUrlForScmm
        ]
    }

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

    static Map templateToMap(String filePath, Map parameters) {
        def hydratedString = new TemplatingEngine().template(new File(filePath), parameters)

        if (hydratedString.trim().isEmpty()) {
            // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
            return [:]
        }
        return new YamlSlurper().parseText(hydratedString) as Map
    }

    @Override
    def createRepo() {
        return null
    }

    @Override
    void init() {

    }
}

}