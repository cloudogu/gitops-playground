package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class AuthorizationInterceptor(
    private val username: String,
    private val password: String
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val newRequest = chain.request().newBuilder()
            .header("Authorization", Credentials.basic(username, password))
            .build()

        return chain.proceed(newRequest)
    }
}
