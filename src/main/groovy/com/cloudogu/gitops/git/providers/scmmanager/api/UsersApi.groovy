package com.cloudogu.gitops.git.providers.scmmanager.api

import retrofit2.Call
import retrofit2.http.*

interface UsersApi {
    @DELETE("v2/users/{id}")
    Call<Void> delete(@Path("id") String id)

    @Headers(["Content-Type: application/vnd.scmm-user+json;v=2"])
    @POST("v2/users")
    Call<Void> addUser(@Body ScmManagerUser user)

    @Headers(["Content-Type: application/vnd.scmm-permissionCollection+json;v=2"])
    @PUT("v2/users/{username}/permissions")
    Call<Void> setPermissionForUser(
            @Path("username") String username,
            @Body Map<String, List<String>> permissions
    )
}