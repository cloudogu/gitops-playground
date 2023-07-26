package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.okhttp.RetryInterceptor
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.annotations.NotNull
import org.slf4j.LoggerFactory

@Factory
class HttpClientFactory {
    @Singleton
    OkHttpClient okHttpClient(HttpLoggingInterceptor httpLoggingInterceptor) {
        return new OkHttpClient.Builder()
                .cookieJar(new JavaNetCookieJar(new CookieManager()))
                .addInterceptor(httpLoggingInterceptor)
                .addInterceptor(new RetryInterceptor())
                .build()
    }

    @Singleton
    HttpLoggingInterceptor createLoggingInterceptor() {
        def logger = LoggerFactory.getLogger("com.cloudogu.gitops.HttpClient")

        def ret = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
            @Override
            void log(@NotNull String msg) {
                logger.trace(msg)
            }
        })

        ret.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        ret.redactHeader("Authorization")

        return ret
    }
}
