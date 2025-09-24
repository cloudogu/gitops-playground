package com.cloudogu.gitops.git.providers.scmmanager.api


import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

/**
 * Parent class for all SCMM Apis that lazily creates the APIs
 */
class ScmManagerApiClient {
    Credentials credentials
    OkHttpClient okHttpClient
    String url

    ScmManagerApiClient(String url, Credentials credentials, Boolean isInsecure) {
        this.url = url
        this.credentials = credentials
        this.okHttpClient = HttpClientFactory.buildOkHttpClient(credentials, isInsecure)
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
        return new Retrofit.Builder()
                .baseUrl(url+'/api/') //TODO check urls
                .client(okHttpClient)
        // Converts HTTP body objects from groovy to JSON
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }
}