package com.cloudogu.gitops.git.providers.scmmanager

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.util.ScmmConfig
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.providers.ScmUrlResolver
import com.cloudogu.gitops.git.providers.scmmanager.api.Repository
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient
import groovy.util.logging.Slf4j
import retrofit2.Response

@Slf4j
class ScmManagerProvider implements GitProvider{

    private final ScmmConfig config
    private final ScmManagerApiClient scmmApiClient

    ScmManagerProvider(ScmmConfig config, ScmManagerApiClient scmmApiClient) {
        this.config = config
        this.scmmApiClient = scmmApiClient
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
    String getUrl() {
        return ""
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
//        return ScmUrlResolver.scmmRepoUrl(config, repoTarget) //TODO
        return ""
    }

    @Override
    Credentials getCredentials() {
        return this.config.credentials
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
