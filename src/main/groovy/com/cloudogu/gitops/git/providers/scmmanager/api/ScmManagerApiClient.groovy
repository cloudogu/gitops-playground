package com.cloudogu.gitops.git.providers.scmmanager.api


import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import groovy.util.logging.Slf4j
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

/**
 * Parent class for all SCMM Apis that lazily creates the APIs
 */
@Slf4j
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

    static handleApiResponse(apiCall,String additionalMessage = "") {
        try {
            def response = apiCall.execute()

            if (!response.isSuccessful()) {
                def errorMessage = "API call failed!'. HTTP Status: ${response.code()} - ${response.message()}"
                if (additionalMessage) {
                    errorMessage += " Additional Info: ${additionalMessage}"
                }
                log.error(errorMessage)
                throw new RuntimeException(errorMessage)
            } else {
                log.info("Successfully completed ${apiCall}")
            }
        } catch (Exception e) {
            def errorMessage = "Error executing API: ${e.message}"
            log.error(errorMessage, e)
            throw new RuntimeException(errorMessage, e)
        }
    }

    protected Retrofit retrofit() {
        return new Retrofit.Builder()
                .baseUrl(this.url)
                .client(okHttpClient)
        // Converts HTTP body objects from groovy to JSON
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
    }
}