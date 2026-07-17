package com.cloudogu.gitops.dependencyinjection;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.dependencyinjection.okhttp.RetryInterceptor;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.AuthorizationInterceptor;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.net.CookieManager;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

@Factory
public class HttpClientFactory {

    public static OkHttpClient buildOkHttpClient(Credentials credentials, Boolean isInsecure) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .addInterceptor(new AuthorizationInterceptor(credentials.getUsername(), credentials.getPassword()))
                .addInterceptor(createLoggingInterceptor())
                .addInterceptor(new RetryInterceptor());

        if (Boolean.TRUE.equals(isInsecure)) {
            InsecureSslContext context = insecureSslContext();
            builder.sslSocketFactory(context.getSocketFactory(), context.getTrustManager());
            builder.hostnameVerifier((hostname, session) -> true);
        }

        return builder.build();
    }

    @Singleton
    @Named("jenkins")
    public OkHttpClient okHttpClientJenkins(Config config) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .cookieJar(new JavaNetCookieJar(new CookieManager()))
                .addInterceptor(createLoggingInterceptor())
                .addInterceptor(new RetryInterceptor());

        if (Boolean.TRUE.equals(config.getApplication().getInsecure())) {
            InsecureSslContext sslContext = insecureSslContext();
            builder.sslSocketFactory(sslContext.getSocketFactory(), sslContext.getTrustManager());
            builder.hostnameVerifier((hostname, session) -> true);
        }

        return builder.build();
    }

    public static HttpLoggingInterceptor createLoggingInterceptor() {
        org.slf4j.Logger logger = LoggerFactory.getLogger("com.cloudogu.gitops.HttpClient");

        HttpLoggingInterceptor ret = new HttpLoggingInterceptor(logger::trace);

        ret.setLevel(HttpLoggingInterceptor.Level.HEADERS);
        ret.redactHeader("Authorization");

        return ret;
    }

    public static InsecureSslContext insecureSslContext() {
        try {
            X509TrustManager noCheckTrustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslCtxt = SSLContext.getInstance("TLS");
            sslCtxt.init(null, new TrustManager[]{noCheckTrustManager}, new SecureRandom());

            return new InsecureSslContext(sslCtxt.getSocketFactory(), noCheckTrustManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct insecure SSL context", e);
        }
    }

    public static class InsecureSslContext {
        private final SSLSocketFactory socketFactory;
        private final X509TrustManager trustManager;

        public InsecureSslContext(SSLSocketFactory socketFactory, X509TrustManager trustManager) {
            this.socketFactory = socketFactory;
            this.trustManager = trustManager;
        }

        public SSLSocketFactory getSocketFactory() {
            return socketFactory;
        }

        public X509TrustManager getTrustManager() {
            return trustManager;
        }
    }
}
