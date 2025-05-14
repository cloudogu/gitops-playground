package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.okhttp.RetryInterceptor
import com.cloudogu.gitops.scmm.api.AuthorizationInterceptor
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.scmm.api.UsersApi
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

    //User
    @Singleton
    @Named("scmm")
    UsersApi usersApi(@Named("scmm") Retrofit retrofit) {
        return retrofit.create(UsersApi)
    }

    @Singleton
    @Named("central-scm")
    UsersApi usersApiCentralScm(@Named("central-scm") Retrofit retrofit) {
        return retrofit.create(UsersApi)
    }


    //Repo
    @Singleton
    @Named("scmm")
    RepositoryApi repositoryApi(@Named("scmm") Retrofit retrofit) {
        return retrofit.create(RepositoryApi)
    }

    @Singleton
    @Named("central-scm")
    RepositoryApi repositoryApiCentralSCM(@Named("central-scm") Retrofit retrofit) {
        return retrofit.create(RepositoryApi)
    }


    //Retrofit
    @Singleton
    @Named("scmm")
    Retrofit retrofit(Config config, @Named("scmm") OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .baseUrl(config.scmm.url + '/api/')
                .client(okHttpClient)
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }

    @Singleton
    @Named("central-scm")
    Retrofit retrofitCentralSCM(Config config, @Named("central-scm") OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .baseUrl(config.multiTenant.centralSCMUrl + '/api/')
                .client(okHttpClient)
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }

    //OkHttpClient
    @Singleton
    @Primary
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

    @Singleton
    @Named("central-scm")
    OkHttpClient centralSCMHttpClient(HttpLoggingInterceptor loggingInterceptor, Config config, Provider<HttpClientFactory.InsecureSslContext> insecureSslContext) {
        def builder = new OkHttpClient.Builder()
                .addInterceptor(new AuthorizationInterceptor(config.multiTenant.username, config.multiTenant.password))
                .addInterceptor(loggingInterceptor)
                .addInterceptor(new RetryInterceptor())

        if (config.application.insecure) {
            def context = insecureSslContext.get()
            builder.sslSocketFactory(context.socketFactory, context.trustManager)
        }

        return builder.build()
    }




}