package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.Scope
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j
import retrofit2.Response

@Slf4j
class ScmManager implements GitProvider {

    private final String releaseName = 'scmm'

    private final Config config
    private final ScmManagerConfig scmmConfig
    private final ScmManagerApiClient scmmApiClient
    private final K8sClient k8sClient
    private final NetworkingUtils networkingUtils

    URI clusterBindAddress
    //TODO unit tests für scmmanager rüberziehen und restlichen Sachen implementieren
    ScmManager(Config config, ScmManagerConfig scmmConfig, K8sClient k8sClient, NetworkingUtils networkingUtils) {
        this.config = config
        this.scmmConfig = scmmConfig
        this.k8sClient = k8sClient
        this.networkingUtils = networkingUtils
        this.scmmApiClient = new ScmManagerApiClient(apiBase().toString(), scmmConfig.credentials, config.application.insecure)
    }


    // =========================================================================================
    // 1) GIT OPERATIONS (repos, permissions, push, credentials, branch/user/delete, GitOps user)
    // =========================================================================================

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        def repoNamespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def repo = new Repository(config.application.namePrefix + repoNamespace, repoName, description ?: "")
        Response<Void> response = scmmApiClient.repositoryApi().create(repo, initialize).execute()
        return handle201or409(response, "Repository ${repoNamespace}/${repoName}")
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        def repoNamespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]

        boolean isGroup = (scope == Scope.GROUP)
        Permission.Role scmManagerRole = mapToScmManager(role)
        def permission = new Permission(principal, scmManagerRole, isGroup)

        Response<Void> response = scmmApiClient.repositoryApi().createPermission(repoNamespace, repoName, permission).execute()
        handle201or409(response, "Permission on ${repoNamespace}/${repoName}")
    }


    /** Client (this process) pushes to …/scm/<rootPath>/<ns>/<name> */
    @Override
    String computePushUrl(String repoTarget) {
        return repoUrlForClient(repoTarget).toString()
    }

    @Override
    Credentials getCredentials() {
        return this.scmmConfig.credentials
    }


    //TODO implement
    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }

    //TODO implement
    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {

    }
    //TODO implement
    @Override
    void deleteUser(String name) {

    }

    @Override
    String getGitOpsUsername() {
        return scmmConfig.gitOpsUsername
    }

    // =========================================================================================
    // 2) IN-CLUSTER / PULL & ENDPOINTS (base URL, pull URL, prefix, protocol/host, Prometheus)
    // =========================================================================================

    /** In-cluster base …/scm (without trailing slash) */
    @Override
    String getUrl() {
        return withoutTrailingSlash(withScm(baseForInCluster())).toString()
    }

    /** In-cluster repo prefix: …/scm/<rootPath>/[<namePrefix>] */
    @Override
    String computeRepoPrefixForInCluster(boolean includeNamePrefix) {
        def base = withSlash(baseForInCluster())    // service DNS oder ingress base
        def root = trimBoth(scmmConfig.rootPath ?: "repo")
        def prefix = trimBoth(config.application.namePrefix ?: "")
        def url = withSlash(base.resolve("scm/${root}")).toString()
        return includeNamePrefix && prefix ? withoutTrailingSlash(URI.create(url + prefix)).toString()
                : withoutTrailingSlash(URI.create(url)).toString()
    }

    /** In-cluster pull: …/scm/<rootPath>/<ns>/<name> */
    @Override
    String computePullUrlForInCluster(String repoTarget) {
        def rt = trimBoth(repoTarget)
        def root = trimBoth(scmmConfig.rootPath ?: "repo")
        return withoutTrailingSlash(withSlash(baseForInCluster()).resolve("scm/${scmmConfig.rootPath}/${rt}/")).toString()
    }


    @Override
    String getProtocol() {
        return baseForInCluster().toString()
    }

    @Override
    String getHost() {
        //in main before:  host : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local" : config.scmm.host(host was config.scmm.url),
        return baseForInCluster().toString()
    }


    /** …/scm/api/v2/metrics/prometheus — client-side, typically scraped externally */
    @Override
    URI prometheusMetricsEndpoint() {
        return withSlash(base()).resolve("api/v2/metrics/prometheus")
    }

    // =========================================================================================
    // 3) URI BUILDING — separated by Client vs. In-Cluster
    // =========================================================================================

    /** Client base …/scm (without trailing slash) */
    URI base() {
        return withoutTrailingSlash(withScm(baseForClient()))
    }

    /** Client API base …/scm/api/ */
    private URI apiBase() {
        return withSlash(base()).resolve("api/")
    }

    /** In-cluster base …/scm (without trailing slash) — for potential in-cluster API calls */
    private URI baseForInClusterScm() {
        return withoutTrailingSlash(withScm(baseForInCluster()))
    }


    /** In-cluster: …/scm/<rootPath> (without trailing slash) */
    URI repoBaseForInCluster() {
        def root = trimBoth(scmmConfig.rootPath ?: "repo")   // <— default & trim
        return withoutTrailingSlash(withSlash(base()).resolve("${root}/"))
    }


    /** Client: …/scm/<rootPath>/<ns>/<name> (without trailing slash) */
    URI repoUrlForClient(String repoTarget) {
        def trimmedRepoTarget = trimBoth(repoTarget)
        return withoutTrailingSlash(withSlash(repoBaseForInCluster()).resolve("${trimmedRepoTarget}/"))
    }


    // =========================================================================================
    // 4) HELPERS & BASE RESOLUTION
    // =========================================================================================

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


    /** Base for *this process* (API client, local git operations) */
    private URI baseForClient() {
        if (Boolean.TRUE == scmmConfig.internal) {
            return config.application.runningInsideK8s ? serviceDnsBase() : hostAccessBase()
        } else {
            return externalBase()
        }
    }


    /** Base for *in-cluster* consumers (Argo CD, jobs) */
    URI baseForInCluster() {
        return scmmConfig.internal ? serviceDnsBase() : externalBase()
    }


    private URI serviceDnsBase() {
        return URI.create("http://scmm.${scmmConfig.namespace}.svc.cluster.local")
    }

    private URI externalBase() {
        // 1) prefer full URL (with scheme)
        def urlString = (scmmConfig.url ?: "").strip()
        if (urlString) return URI.create(urlString)

        // 2) otherwise, ingress host (no scheme), default to http
        def ingressHost = (scmmConfig.ingress ?: "").strip()
        if (ingressHost) return URI.create("http://${ingressHost}")

        // 3) hard fail — when internal=false one of the above must be set
        throw new IllegalArgumentException(
                "Either scmmConfig.url or scmmConfig.ingress must be set when internal=false"
        )
    }

    private URI hostAccessBase() {
        if(this.clusterBindAddress){
            return this.clusterBindAddress
        }
        final def port = k8sClient.waitForNodePort(releaseName, scmmConfig.namespace)
        final def host = networkingUtils.findClusterBindAddress()
        this.clusterBindAddress=new URI("http://${host}:${port}")
        return this.clusterBindAddress
    }

    private static URI withScm(URI uri) {
        def uriWithSlash = withSlash(uri)
        def urlPath = uriWithSlash.path ?: ""
        def endsWithScm = urlPath.endsWith("/scm/")
        return endsWithScm ? uriWithSlash : uriWithSlash.resolve("scm/")
    }

    private static URI withSlash(URI uri) {
        def urlString = uri.toString()
        return urlString.endsWith('/') ? uri : URI.create(urlString + '/')
    }

    private static URI withoutTrailingSlash(URI uri) {
        def urlString = uri.toString()
        return urlString.endsWith('/') ? URI.create(urlString.substring(0, urlString.length() - 1)) : uri
    }

    //Removes leading and trailing slashes (prevents absolute paths when using resolve).
    private static String trimBoth(String str) {
        return (str ?: "").replaceAll('^/+', '').replaceAll('/+$', '')
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