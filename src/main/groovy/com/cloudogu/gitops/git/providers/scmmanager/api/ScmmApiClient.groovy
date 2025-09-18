package com.cloudogu.gitops.git.providers.scmmanager.api

import com.cloudogu.gitops.config.Config
import jakarta.inject.Named
import jakarta.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

/**
 * Parent class for all SCMM Apis that lazily creates the APIs, so the latest SCMM-URL is used
 */
@Singleton
class ScmmApiClient {
    Config config
    OkHttpClient okHttpClient

    ScmmApiClient(Config config, @Named("scmm") OkHttpClient okHttpClient) {
        this.config = config
        this.okHttpClient = okHttpClient
    }

    UsersApi usersApi() {
        return retrofit().create(UsersApi)
    }

    RepositoryApi repositoryApi() {
        return retrofit().create(RepositoryApi)
    }

    ScmmApi generalApi() {
        return retrofit().create(ScmmApi)
    }

    PluginApi pluginApi() {
        return retrofit().create(PluginApi)
    }

    protected Retrofit retrofit() {
        return new Retrofit.Builder()
                .baseUrl(config.multiTenant.scmmConfig.centralScmUrl + '/api/') // TODO: Anna right URL
                .client(okHttpClient)
        // Converts HTTP body objects from groovy to JSON
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }
}