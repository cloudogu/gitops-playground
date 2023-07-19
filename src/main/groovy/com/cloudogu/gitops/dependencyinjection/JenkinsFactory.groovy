package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.jenkins.ApiClient
import com.cloudogu.gitops.jenkins.Configuration
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import okhttp3.OkHttpClient

@Factory
class JenkinsFactory {
    private Configuration config

    JenkinsFactory(Configuration config) {
        this.config = config
    }

    @Singleton
    ApiClient jenkinsApiClient(OkHttpClient client) {
        return new ApiClient(
                config.url,
                config.username,
                config.password,
                client
        )
    }
}
