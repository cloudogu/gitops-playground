package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.cli.JenkinsCli
import com.cloudogu.gitops.jenkins.ApiClient
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import okhttp3.OkHttpClient

@Factory
class JenkinsFactory {
    private JenkinsCli.OptionsMixin config

    JenkinsFactory(JenkinsCli.OptionsMixin config) {
        this.config = config
    }

    @Singleton
    ApiClient jenkinsApiClient(OkHttpClient client) {
        return new ApiClient(
                config.jenkinsUrl,
                config.jenkinsUsername,
                config.jenkinsPassword,
                client
        )
    }
}
