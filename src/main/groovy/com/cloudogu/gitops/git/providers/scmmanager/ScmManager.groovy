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

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def repo = new Repository(config.application.namePrefix + namespace, repoName, description ?: "")
        Response<Void> response = scmmApiClient.repositoryApi().create(repo, initialize).execute()
        return handle201or409(response, "Repository ${namespace}/${repoName}")
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]

        boolean isGroup = (scope == Scope.GROUP)
        Permission.Role scmManagerRole = mapToScmManager(role)
        def permission = new Permission(principal, scmManagerRole, isGroup)

        Response<Void> response = scmmApiClient.repositoryApi().createPermission(namespace, repoName, permission).execute()
        handle201or409(response, "Permission on ${namespace}/${repoName}")
    }

    /** …/scm/<rootPath>/<ns>/<name> */
    @Override
    String computePushUrl(String repoTarget) {
        repoUrl(repoTarget).toString()
    }

    @Override
    Credentials getCredentials() {
        return this.scmmConfig.credentials
    }

    @Override
    String getUrl() {
        /** …/scm/<rootPath>/nameprefix */
        return withoutTrailingSlash(withSlash(repoBase()).resolve("${config.application.namePrefix}")).toString()
    }

    @Override
    String getProtocol() {
        if (scmmConfig.internal) {
            return "http"
        } else {
            return scmmConfig.url
        }
    }

    @Override
    String getHost() {
        //in main before:  host : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local" : config.scmm.host(host was config.scmm.url),
        return resolveEndpoint().toString()
    }

    @Override
    String getGitOpsUsername() {
        return scmmConfig.gitOpsUsername
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


    // ---------------- URI components  ----------------


    /** …/scm  (without trailing slash) */
    URI base() {
        return withoutTrailingSlash(withScm(resolveEndpoint()))
    }

    /** …/scm/api/ */  // apiBase for ScmManagerApiClient ?
    private URI apiBase() {
        return withSlash(base()).resolve("api/")
    }


    /** …/scm/<rootPath>  (without trailing slash; rootPath default = "repo") */
    URI repoBase() {
        def root = trimBoth(scmmConfig.rootPath ?: "repo")   // <— default & trim
        return withoutTrailingSlash(withSlash(base()).resolve("${root}/"))
    }


    /** …/scm/<rootPath>/<ns>/<name>  (without trailing slash) */
    URI repoUrl(String repoTarget) {
        def trimmedRepoTarget = trimBoth(repoTarget)
        return withoutTrailingSlash(withSlash(repoBase()).resolve("${trimmedRepoTarget}/"))
    }


    /** …/scm/api/v2/metrics/prometheus */
    URI prometheusMetricsEndpoint() {
        return withSlash(base()).resolve("api/v2/metrics/prometheus")
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

    // --- helpers ---
//    private URI internalOrExternal() {
//        if (scmmConfig.internal) {
//            if(!config.application.runningInsideK8s){
//                return new URI(this.clusterBindAddress)
//            }
//            return URI.create("http://scmm.${config.application.namePrefix}${scmmConfig.namespace}.svc.cluster.local")
//        }
//        def urlString = (scmmConfig.url ?: '').strip()
//        if (!urlString) {
//            throw new IllegalArgumentException("scmmConfig.url must be set when scmmConfig.internal = false")
//        }
//        // TODO do we need here to consider scmmConfig.ingeress? URI.create("https://${scmmConfig.ingress}"
//        return URI.create(urlString)
//    }

    private URI resolveEndpoint() {
        return scmmConfig.internal ? internalEndpoint() : externalEndpoint()
    }

    private URI internalEndpoint() {
        def namespace = resolvedNamespace() // namePrefix + namespace
        if (config.application.runningInsideK8s) {
            return URI.create("http://scmm.${namespace}.svc.cluster.local/scm")
        } else {
            if(this.clusterBindAddress){
                return this.clusterBindAddress
            }
            def port = k8sClient.waitForNodePort(releaseName, namespace)
            def host = networkingUtils.findClusterBindAddress()
            this.clusterBindAddress=new URI("http://${host}:${port}")
            return this.clusterBindAddress.resolve("/scm")
        }
    }

    private URI externalEndpoint() {
        def urlString = (scmmConfig.url ?: '').strip()
        if (urlString) return URI.create(urlString)
        def host = (scmmConfig.ingress ?: '').strip()
        if (host) return URI.create("http://${host}/scm")
        throw new IllegalArgumentException("Either scmmConfig.url or scmmConfig.ingress must be set when scmmConfig.internal=false")
    }

    private String resolvedNamespace() {
        def prefix = (config.application.namePrefix ?: "")
        def ns = (scmmConfig.namespace ?: "scm-manager")
        return "${prefix}${ns}"
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