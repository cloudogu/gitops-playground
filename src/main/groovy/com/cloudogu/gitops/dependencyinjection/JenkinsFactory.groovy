package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.jenkins.ApiClient
import com.cloudogu.gitops.jenkins.JenkinsConfiguration
import io.micronaut.context.annotation.Factory
import jakarta.inject.Named
import jakarta.inject.Singleton
import okhttp3.OkHttpClient

@Factory
class JenkinsFactory {
    private JenkinsConfiguration config

    JenkinsFactory(JenkinsConfiguration config) {
        this.config = config
    }

    @Singleton
    ApiClient jenkinsApiClient(@Named("jenkins") OkHttpClient client) {
        return new ApiClient(
                config.url,
                config.username,
                config.password,
                client
        )
    }
}
