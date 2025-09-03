package com.cloudogu.gitops.git.scmm.api

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface PluginApi {
    @POST("v2/plugins/available/{name}/install")
    Call<ResponseBody> install(@Path("name") String name, @Query("restart") Boolean restart)

    @PUT("api/v2/config/jenkins/")
    @Headers("Content-Type: application/json")
    Call<Void> configureJenkinsPlugin(@Body Map<String, Object> config)
}