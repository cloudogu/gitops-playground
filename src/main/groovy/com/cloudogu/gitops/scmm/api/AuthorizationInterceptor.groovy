package com.cloudogu.gitops.scmm.api


import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import org.jetbrains.annotations.NotNull

class AuthorizationInterceptor implements Interceptor {
    private String username
    private String password

    AuthorizationInterceptor(String username, String password) {
        this.username = username
        this.password = password
    }

    @Override
    Response intercept(@NotNull Chain chain) throws IOException {
        def newRequest = chain.request().newBuilder()
                .header("Authorization", Credentials.basic(username, password))
                .build()

        return chain.proceed(newRequest)
    }
}
