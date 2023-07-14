package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.okhttp.RetryInterceptor
import com.cloudogu.gitops.utils.InMemoryCookieJar
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import okhttp3.OkHttpClient

@Factory
class HttpClientFactory {
    @Singleton
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar())
                .addInterceptor(new RetryInterceptor())
                .build()
    }
}
