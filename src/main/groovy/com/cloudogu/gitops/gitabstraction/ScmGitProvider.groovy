package com.cloudogu.gitops.gitabstraction

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import groovy.util.logging.Slf4j
import retrofit2.Response

@Slf4j
class ScmGitProvider extends BaseGitProvider implements GitProvider{

    private final Config config
    private final ScmmApiClient scmmApiClient

    ScmGitProvider(Config config, ScmmApiClient scmmApiClient) {
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
    void setDefaultBranch(String repoTarget, String branch) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def body = [ defaultBranch: (branch ?: "main") ]
        Response<Void> response = scmmApiClient.gitConfigApi().setDefaultBranch(namespace, repoName, body).execute()
        handleOk(response, "set default branch for ${namespace}/${repoName}") // accept 200/204

    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {
        def namespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]
        def permission = new Permission(principal, role, groupPermission)
        Response<Void> response = scmmApiClient.repositoryApi().createPermission(namespace, repoName, permission).execute()
        handle201or409(response, "Permission on ${namespace}/${repoName}")
    }

    //TODO here use the right URL
    @Override
    String computePushUrl(String repoTarget) {
        // SCMM Push-URL: <protocol>://<host>/<rootPath>/<namespace>/<name>
        return "${config.scmm.protocol}://${config.scmm.host}/${config.scmm.rootPath}/${repoTarget}"

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

    // For PUT/PATCH/DELETE : 200/204 are interpreted as OK
    private static void handleOk(Response<?> response, String what, Set<Integer> ok = [200, 204] as Set) {
        int code = response.code()
        if (!ok.contains(code)) {
            throw new RuntimeException("Failde to ${what}" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
    }


}
