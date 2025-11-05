package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j
import retrofit2.Response

@Slf4j
class ScmManager implements GitProvider {

    private final ScmManagerUrlResolver urls
    private final ScmManagerApiClient apiClient
    private final ScmManagerConfig scmmConfig

    ScmManager(Config config, ScmManagerConfig scmmConfig, K8sClient k8sClient, NetworkingUtils networkingUtils) {
        this.scmmConfig = scmmConfig
        this.urls = new ScmManagerUrlResolver(config, scmmConfig, k8sClient, networkingUtils)
        this.apiClient = new ScmManagerApiClient(urls.clientApiBase().toString(), scmmConfig.credentials, config.application.insecure)
    }


    // --- Git operations ---
    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        def repoNamespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def repo = new Repository(repoNamespace, repoName, description ?: "")
        Response<Void> response = apiClient.repositoryApi().create(repo, initialize).execute()
        return handle201or409(response, "Repository ${repoNamespace}/${repoName}")
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        def repoNamespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]

        boolean isGroup = (scope == Scope.GROUP)
        Permission.Role scmManagerRole = mapToScmManager(role)
        def permission = new Permission(principal, scmManagerRole, isGroup)

        Response<Void> response = apiClient.repositoryApi().createPermission(repoNamespace, repoName, permission).execute()
        handle201or409(response, "Permission on ${repoNamespace}/${repoName}")
    }

    @Override
    Credentials getCredentials() {
        return this.scmmConfig.credentials
    }


    @Override
    String getGitOpsUsername() {
        return scmmConfig.gitOpsUsername
    }

    // --- In-cluster / Endpoints ---
    /** In-cluster base …/scm (without trailing slash) */
    @Override
    String getUrl() {
        return urls.inClusterBase().toString()
    }

    /** In-cluster repo prefix: …/scm/<rootPath>/[<namePrefix>] */
    @Override
    String repoPrefix() {
        return urls.inClusterRepoPrefix()
    }


    /**  …/scm/<rootPath>/<ns>/<name> */
    @Override
    String repoUrl(String repoTarget, RepoUrlScope scope) {
        switch (scope) {
            case RepoUrlScope.CLIENT:
                return urls.clientRepoUrl(repoTarget)
            case RepoUrlScope.IN_CLUSTER:
                return urls.inClusterRepoUrl(repoTarget)
            default:
                return urls.inClusterRepoUrl(repoTarget)
        }
    }

    @Override
    String getProtocol() {
        return urls.inClusterBase().scheme   // e.g. "http"
    }

    @Override
    String getHost() {
        return urls.inClusterBase().host // e.g. "scmm.ns.svc.cluster.local"
    }

    /** …/scm/api/v2/metrics/prometheus — client-side, typically scraped externally */
    @Override
    URI prometheusMetricsEndpoint() {
        return urls.prometheusEndpoint()
    }

    /**
     * No-op by design. Not used: ScmmDestructionHandler deletes repositories via ScmManagerApiClient.
     * Kept for interface compatibility only. */
    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {
        // intentionally left blank
    }

    /**
     * No-op by design. Not used: ScmmDestructionHandler deletes users via ScmManagerApiClient.
     * Kept for interface compatibility only. */
    @Override
    void deleteUser(String name) {
        // intentionally left blank
    }

    /**
     * No-op by design. Default branch management is not implemented via this abstraction.
     * Kept for interface compatibility only.
     */
    @Override
    void setDefaultBranch(String repoTarget, String branch) {
        // intentionally left blank
    }

    // --- helpers ---
    private static Permission.Role mapToScmManager(AccessRole role) {
        switch (role) {
            case AccessRole.READ: return Permission.Role.READ
            case AccessRole.WRITE: return Permission.Role.WRITE
            case AccessRole.MAINTAIN:
                // SCM-manager doesn't know  MAINTAIN -> downgrade to WRITE
                log.warn("SCM-Manager: Mapping MAINTAIN → WRITE")
                return Permission.Role.WRITE
            case AccessRole.ADMIN: return Permission.Role.OWNER
            case AccessRole.OWNER: return Permission.Role.OWNER
        }
    }

    private static boolean handle201or409(Response<?> response, String what) {
        int code = response.code()
        if (code == 409) {
            log.debug("${what} already exists — ignoring (HTTP 409)")
            return false
        } else if (code != 201) {
            throw new RuntimeException("Could not create ${what}" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
        return true// because its created
    }

    /** Test-only constructor (package-private on purpose). */
    ScmManager(Config config, ScmManagerConfig scmmConfig,
               ScmManagerUrlResolver urls,
               ScmManagerApiClient apiClient) {
        this.scmmConfig = Objects.requireNonNull(scmmConfig, "scmmConfig must not be null")
        this.urls = Objects.requireNonNull(urls, "urls must not be null")
        this.apiClient = apiClient ?: new ScmManagerApiClient(
                urls.clientApiBase().toString(),
                scmmConfig.credentials,
                Objects.requireNonNull(config, "config must not be null").application.insecure
        )
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