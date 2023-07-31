package com.cloudogu.gitops.scmm.api

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.DELETE
import retrofit2.http.Path

interface UsersApi {
    @DELETE("v2/users/{id}")
    Call<ResponseBody> delete(@Path("id") String id)
}
