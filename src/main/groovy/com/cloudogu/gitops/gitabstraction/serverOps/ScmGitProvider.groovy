package com.cloudogu.gitops.gitabstraction.serverOps

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmUrlResolver
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import groovy.util.logging.Slf4j
import retrofit2.Response

@Slf4j
class ScmGitProvider extends BaseGitProvider implements GitProvider{

    private final Config config
    private final ScmmApiClient scmmApiClient
    private final ScmUrlResolver urlResolver

    ScmGitProvider(Config config, ScmmApiClient scmmApiClient, ScmUrlResolver urlResolver) {
        this.config = config
        this.scmmApiClient = scmmApiClient
        this.urlResolver = urlResolver
    }

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def repo = new Repository(namespace, repoName, description ?: "")
        Response<Void> response = scmmApiClient.repositoryApi().create(repo, initialize).execute()
        return handle201or409(response, "Repository ${namespace}/${repoName}")
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def permission = new Permission(principal, role, groupPermission)
        Response<Void> response = scmmApiClient.repositoryApi().createPermission(namespace, repoName, permission).execute()
        handle201or409(response, "Permission on ${namespace}/${repoName}")
    }

    @Override
    String computePushUrl(String repoTarget) {
        return urlResolver.scmmRepoUrl(config, repoTarget)
    }

    @Override
    GitPushAuth pushAuth(boolean isCentralRepo) {
        def username = isCentralRepo ? config.multiTenant.username : config.scmm.username
        def password = isCentralRepo ? config.multiTenant.password : config.scmm.password
        return new GitPushAuth(username as String, password as String)
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

}
