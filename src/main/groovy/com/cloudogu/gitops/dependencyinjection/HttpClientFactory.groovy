package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.okhttp.RetryInterceptor
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient

@Factory
class HttpClientFactory {
    @Singleton
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .cookieJar(new JavaNetCookieJar(new CookieManager()))
                .addInterceptor(new RetryInterceptor())
                .build()
    }
}
