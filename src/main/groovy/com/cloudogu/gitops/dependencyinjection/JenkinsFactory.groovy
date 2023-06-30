package com.cloudogu.gitops.dependencyinjection

import com.cloudogu.gitops.cli.JenkinsCli
import com.cloudogu.gitops.jenkins.ApiClient
import com.cloudogu.gitops.okhttp.RetryInterceptor
import com.cloudogu.gitops.utils.InMemoryCookieJar
import jakarta.inject.Singleton
import okhttp3.OkHttpClient
import io.micronaut.context.annotation.Factory

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

    @Singleton
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar())
                .addInterceptor(new RetryInterceptor())
                .build()
    }
}
