package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.jenkins.JenkinsConfiguration
import com.cloudogu.gitops.okhttp.RetryInterceptor
import io.micronaut.context.annotation.Factory
import jakarta.inject.Named
import jakarta.inject.Singleton
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.annotations.NotNull
import org.slf4j.LoggerFactory

import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

@Factory
class HttpClientFactory {
    @Singleton
    @Named("jenkins")
    OkHttpClient okHttpClient(HttpLoggingInterceptor httpLoggingInterceptor, JenkinsConfiguration jenkinsConfiguration) {
        def builder = new OkHttpClient.Builder()
                .cookieJar(new JavaNetCookieJar(new CookieManager()))
                .addInterceptor(httpLoggingInterceptor)
                .addInterceptor(new RetryInterceptor())

        if (jenkinsConfiguration.insecure) {
            def noCheckTrustManager = new X509TrustManager() {
                @Override
                void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0]
                }
            }
            def sslCtxt = SSLContext.getInstance('SSL')
            sslCtxt.init(null, [noCheckTrustManager] as X509TrustManager[], new SecureRandom())
            builder.sslSocketFactory(sslCtxt.socketFactory, noCheckTrustManager)
        }

        return builder.build()
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
