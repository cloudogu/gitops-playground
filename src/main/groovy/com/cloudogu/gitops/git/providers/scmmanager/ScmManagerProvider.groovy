package com.cloudogu.gitops.git.providers.scmmanager


import com.cloudogu.gitops.features.git.config.util.ScmmConfig
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import groovy.util.logging.Slf4j
import retrofit2.Response

@Slf4j
class ScmManagerProvider implements GitProvider{

    private final ScmmConfig scmmConfig
    private final ScmManagerApiClient scmmApiClient  //TODO apiclient erstellen(jede Instanz erstellt selber einen apiclient)

    //TODO add genearal config for nameprefix in internal
    ScmManagerProvider(ScmmConfig scmmConfig, ScmManagerApiClient scmmApiClient) {
        this.scmmConfig = scmmConfig
        this.scmmApiClient = scmmApiClient
    }

    /** …/scm/api/ */  // apiBase for ScmManagerApiClient ?
    private URI apiBase() {
        return withSlash(base()).resolve("api/")
    }

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def repo = new Repository(namespace, repoName, description ?: "")
        Response<Void> response = scmmApiClient.repositoryApi().create(repo, initialize).execute()
        return handle201or409(response, "Repository ${namespace}/${repoName}")
    }

    // TODO what kind of url (repoUrl/repoBase)? than rename to repoUrl?
    @Override
    String getUrl() {
        return repoBase()
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def permission = new Permission(principal, role, groupPermission)
        Response<Void> response = scmmApiClient.repositoryApi().createPermission(namespace, repoName, permission).execute()
        handle201or409(response, "Permission on ${namespace}/${repoName}")
    }

    /** …/scm/<rootPath>/<ns>/<name>.git */
    @Override
    String computePushUrl(String repoTarget) {
       repoUrl(repoTarget).toString() + ".git"
    }

    @Override
    Credentials getCredentials() {
        return this.scmmConfig.credentials
    }

    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {
       log.info("test")
    }
    //TODO implement
    @Override
    void deleteUser(String name) {

    }

    //TODO implement
    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }

    // ---------------- URL components  ----------------
    /** …/scm/api/v2/metrics/prometheus */
    URI prometheusMetricsEndpoint() {
        return withSlash(base()).resolve("api/v2/metrics/prometheus")
    }

    /** …/scm  (without trailing slash) */
    URI base() {
        return withoutTrailingSlash(withScm(internalOrExternal()))
    }

    /** …/scm/<rootPath>  (without trailing slash; rootPath default = "repo") */
    URI repoBase() {
        def root = trimBoth(scmmConfig.rootPath ?: "repo")   // <— default & trim
        if (!root) return base()
        return withoutTrailingSlash( withSlash(base()).resolve("${root}/"))
    }

    /** …/scm/<rootPath>/<ns>/<name>  (without trailing slash) */
    URI repoUrl(String repoTarget) {
        def trimmedRepoTarget = trimBoth(repoTarget)
        return withoutTrailingSlash(withSlash(repoBase()).resolve("${trimmedRepoTarget}/"))
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
    private URI internalOrExternal() {
        if (scmmConfig.internal) { //TODO namePrefix should be here
            return URI.create("http://scmm.${scmmConfig.namespace}.svc.cluster.local")
        }
        def urlString = (scmmConfig.url ?: '').strip()
        if (!urlString) {
            throw new IllegalArgumentException("scmmConfig.url must be set when scmmConfig.internal = false")
        }
        // TODO do we need here to consider scmmConfig.ingeress? URI.create("https://${scmmConfig.ingress}"
        return URI.create(urlString)
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

    private static URI withoutTrailingSlash(URI uri){
        def urlString = uri.toString()
        return urlString.endsWith('/') ? URI.create(urlString.substring(0, urlString.length() - 1)) : uri
    }

    //Removes leading and trailing slashes (prevents absolute paths when using resolve).
    private static String trimBoth(String str) {
        return (str ?: "").replaceAll('^/+', '').replaceAll('/+$','')
    }

}
