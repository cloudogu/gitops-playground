package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.api.AuthorizationInterceptor
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.scmm.api.UsersApi
import io.micronaut.context.annotation.Factory
import jakarta.inject.Named
import jakarta.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

@Factory
class RetrofitFactory {
    @Singleton
    UsersApi usersApi(Retrofit retrofit) {
        return retrofit.create(UsersApi)
    }

    @Singleton
    RepositoryApi repositoryApi(Retrofit retrofit) {
        return retrofit.create(RepositoryApi)
    }

    @Singleton
    Retrofit retrofit(Configuration configuration, @Named("scmm") OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .baseUrl(configuration.config.scmm['url'] as String + '/api/')
                .client(okHttpClient)
                .build()
    }

    @Singleton
    @Named("scmm")
    OkHttpClient okHttpClient(HttpLoggingInterceptor loggingInterceptor, Configuration configuration) {
        return new OkHttpClient.Builder()
                .addInterceptor(new AuthorizationInterceptor(configuration.config.scmm['username'] as String, configuration.config.scmm['password'] as String))
                .addInterceptor(loggingInterceptor)
                .build()
    }
}
