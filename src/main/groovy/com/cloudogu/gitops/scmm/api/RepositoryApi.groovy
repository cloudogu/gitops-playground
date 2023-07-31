package com.cloudogu.gitops.scmm.api

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.DELETE
import retrofit2.http.Path

interface RepositoryApi {
    @DELETE("v2/repositories/{namespace}/{name}")
    Call<ResponseBody> delete(@Path("namespace") String namespace, @Path("name") String name)
}
