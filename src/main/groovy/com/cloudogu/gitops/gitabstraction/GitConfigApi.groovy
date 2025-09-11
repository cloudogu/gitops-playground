package com.cloudogu.gitops.gitabstraction

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path

interface GitConfigApi {
    @Headers("Content-Type: application/vnd.scmm-gitConfig+json")
    @PUT("/api/v2/config/git/{namespace}/{name}")
    Call<Void> setDefaultBranch(
            @Path("namespace") String namespace,
            @Path("name") String name,
            @Body Map<String, String> body // e.g. [defaultBranch: "main"]
    )
}