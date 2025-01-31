package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.okhttp.RetryInterceptor
import com.cloudogu.gitops.scmm.api.*
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Primary
import jakarta.inject.Named
import jakarta.inject.Provider
import jakarta.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

@Factory
class RetrofitFactory {
    @Singleton
    UsersApi usersApi(@Named("scmm") Retrofit retrofit) {
        return retrofit.create(UsersApi)
    }

    @Singleton
    RepositoryApi repositoryApi(@Named("scmm") Retrofit retrofit) {
        return retrofit.create(RepositoryApi)
    }

    @Singleton
    @Primary
    @Named("scmm")
    Retrofit retrofit(Config config, @Named("scmm") OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .baseUrl(config.scmm.url + '/api/')
                .client(okHttpClient)
                 // Converts HTTP body objects from groovy to JSON
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }

    @Singleton
    @Named("scmm")
    OkHttpClient okHttpClient(HttpLoggingInterceptor loggingInterceptor, Config config, Provider<HttpClientFactory.InsecureSslContext> insecureSslContext) {
        def builder = new OkHttpClient.Builder()
                .addInterceptor(new AuthorizationInterceptor(config.scmm.username, config.scmm.password))
                .addInterceptor(loggingInterceptor)
                .addInterceptor(new RetryInterceptor())

        if (config.application.insecure) {
            def context = insecureSslContext.get()
            builder.sslSocketFactory(context.socketFactory, context.trustManager)
        }

        return builder.build()
    }

}
