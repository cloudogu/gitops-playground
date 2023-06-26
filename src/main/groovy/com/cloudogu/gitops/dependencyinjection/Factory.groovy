package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.cli.JenkinsCli
import com.cloudogu.gitops.jenkins.ApiClient
import com.cloudogu.gitops.utils.InMemoryCookieJar
import jakarta.inject.Singleton
import okhttp3.OkHttpClient

@io.micronaut.context.annotation.Factory
class Factory {
    private JenkinsCli.OptionsMixin config

    Factory(JenkinsCli.OptionsMixin config) {
        this.config = config
    }

    @Singleton
    ApiClient jenkinsApiClient(OkHttpClient client) {
        return new ApiClient(
                config.jenkinsUrl as String,
                config.jenkinsUsername as String,
                config.jenkinsPassword as String,
                client
        )
    }

    @Singleton
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar())
                .build()
    }
}
