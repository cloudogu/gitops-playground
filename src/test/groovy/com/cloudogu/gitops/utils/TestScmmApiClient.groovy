package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitabstraction.serverOps.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import okhttp3.internal.http.RealResponseBody
import okio.BufferedSource
import retrofit2.Call
import retrofit2.Response

import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when 

class TestScmmApiClient extends ScmmApiClient {

    RepositoryApi repositoryApi = mock(RepositoryApi)
    Set<String> createdRepos = new HashSet<>()
    Set<String> createdPermissions = new HashSet<>()

    TestScmmApiClient(Config config) {
        super(config, null)
    }

    @Override
    RepositoryApi repositoryApi() {
        return repositoryApi
    }

    /**
     * Make all repo API calls return created on the first call and exists on subsequent calls for each repo.
     */
    void mockRepoApiBehaviour() {
        def responseCreated = mockSuccessfulResponse(201)
        def responseExists = mockErrorResponse(409)

        when(repositoryApi.create(any(Repository), anyBoolean()))
                .thenAnswer { invocation ->
                    Repository repo = invocation.getArgument(0)
                    if (createdRepos.contains(repo.fullRepoName)) {
                        return responseExists
                    } else {
                        createdRepos.add(repo.fullRepoName)
                        return responseCreated
                    }
                }
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission)))
                .thenAnswer { invocation ->
                    String namespace= invocation.getArgument(0)
                    String name = invocation.getArgument(1)
                    if (createdPermissions.contains("${namespace}/${name}".toString())) {
                        return responseExists
                    } else {
                        createdPermissions.add("${namespace}/${name}".toString())
                        return responseCreated
                    }
                }
    }

    static Call<Void> mockSuccessfulResponse(int expectedReturnCode) {
        def expectedCall = mock(Call<Void>)
        when(expectedCall.execute()).thenReturn(Response.success(expectedReturnCode, null))
        expectedCall
    }

    static Call<Void> mockErrorResponse(int expectedReturnCode) {
        def expectedCall = mock(Call<Void>)
        // Response is a final class that cannot be mocked 😠
        Response<Void> errorResponse = Response.error(expectedReturnCode, new RealResponseBody('dontcare', 0, mock(BufferedSource)))
        when(expectedCall.execute()).thenReturn(errorResponse)
        expectedCall
    }
}
