package com.cloudogu.gitops.git.providers.scmmanager.api

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import com.cloudogu.gitops.features.git.config.util.ScmmConfig
import jakarta.inject.Named
import jakarta.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

/**
 * Parent class for all SCMM Apis that lazily creates the APIs, so the latest SCMM-URL is used
 */
@Singleton
class ScmManagerApiClient {
    ScmmConfig scmmConfig
    OkHttpClient okHttpClient

    ScmManagerApiClient(ScmmConfig scmmConfig) {
        this.scmmConfig = scmmConfig
        this.okHttpClient = HttpClientFactory.buildOkHttpClient(scmmConfig)
    }

    UsersApi usersApi() {
        return retrofit().create(UsersApi)
    }

    RepositoryApi repositoryApi() {
        return retrofit().create(RepositoryApi)
    }

    ScmManagerApi generalApi() {
        return retrofit().create(ScmManagerApi)
    }

    PluginApi pluginApi() {
        return retrofit().create(PluginApi)
    }

    protected Retrofit retrofit() {
        return new Retrofit.Builder() //TODO support both scmms
                .baseUrl(config.multiTenant.scmmConfig.url + '/api/') // TODO: Anna right URL
                .client(okHttpClient)
        // Converts HTTP body objects from groovy to JSON
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }
}