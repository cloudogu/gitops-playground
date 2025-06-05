package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import okhttp3.internal.http.RealResponseBody
import okio.BufferedSource
import retrofit2.Call
import retrofit2.Response

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when 

class TestScmmApiClient extends ScmmApiClient {

    RepositoryApi repositoryApi = mock(RepositoryApi)

    TestScmmApiClient(Config config) {
        super(config, null)
    }

    @Override
    RepositoryApi repositoryApi() {
        return repositoryApi
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
