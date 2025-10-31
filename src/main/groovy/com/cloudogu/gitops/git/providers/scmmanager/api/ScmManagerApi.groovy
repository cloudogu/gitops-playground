package com.cloudogu.gitops.git.providers.scmmanager.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PUT

interface ScmManagerApi {

    @GET("api/v2")
    Call<Void> checkScmmAvailable()

    @PUT("api/v2/config")
    @Headers("Content-Type: application/vnd.scmm-config+json;v=2")
    Call<Void> setConfig(@Body Map<String, Object> config)
}