package com.cloudogu.gitops.git.providers.scmmanager

import groovy.util.logging.Slf4j

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.config.util.ScmManagerConfig
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils

import retrofit2.Response

@Slf4j
class ScmManager implements GitProvider {

    ScmManagerUrlResolver urls
    ScmManagerApiClient apiClient
    ScmManagerConfig scmmConfig

    NetworkingUtils networkingUtils
    HelmStrategy helmStrategy
    K8sClient k8sClient
    Config config
    ScmManagerSetup scmManagerSetup

    ScmManager(Config config, ScmManagerConfig scmmConfig, HelmStrategy helmStrategy, K8sClient k8sClient, NetworkingUtils networkingUtils, Boolean installNeeded = false) {
        this.scmmConfig = scmmConfig
        this.config = config
        this.helmStrategy = helmStrategy
        this.k8sClient = k8sClient
        this.networkingUtils = networkingUtils
        init(installNeeded)
    }

    void init(installNeeded) {
        // --- Init Setup ---
        if (this.scmmConfig.internal && installNeeded) {
            this.scmManagerSetup = new ScmManagerSetup(this)
            this.scmManagerSetup.setupHelm()
            this.urls = new ScmManagerUrlResolver(this.config, this.scmmConfig, this.k8sClient, this.networkingUtils)
            this.apiClient = new ScmManagerApiClient(this.urls.clientApiBase().toString(), this.scmmConfig.credentials, this.config.application.insecure)
            this.scmManagerSetup.waitForScmmAvailable()
            this.scmManagerSetup.configure()
        } else {
            this.urls = new ScmManagerUrlResolver(this.config, this.scmmConfig, this.k8sClient, this.networkingUtils)
            this.apiClient = new ScmManagerApiClient(this.urls.clientApiBase().toString(), this.scmmConfig.credentials, this.config.application.insecure)
        }
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
}