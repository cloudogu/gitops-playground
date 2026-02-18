package com.cloudogu.gitops.jenkins

import jakarta.inject.Named
import jakarta.inject.Singleton

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import com.cloudogu.gitops.config.Config

import okhttp3.*

@Slf4j
@Singleton
class JenkinsApiClient {
    private Config config

    private OkHttpClient client

    // Number of retries in uncommonly high, because we might have to  outlive a unexpected Jenkins restart
    private int maxRetries = 180
    private int waitPeriodInMs = 2000

    JenkinsApiClient(
            Config config,
            @Named("jenkins") OkHttpClient client
    ) {

        if (config.application.insecure) {
            this.client = client.newBuilder()
                    .hostnameVerifier({ hostname, session -> true })
                    .build()
        } else {
            this.client = client
        }
        this.config = config
    }

    String runScript(String code) {
        log.trace("Running groovy script in Jenkins: {}", code)
        def response = postRequestWithCrumb("scriptText", new FormBody.Builder().add("script", code).build())
        if (response.code() != 200) {
            throw new RuntimeException("Could not run script. Status code ${response.code()}")
        }

        return response.body().string()
    }

    Response postRequestWithCrumb(String url, RequestBody postData = null) {
        return sendRequestWithRetries {
            Request.Builder request = buildRequest(url)
                .header("Jenkins-Crumb", getCrumb())

            if (postData != null) {
                request.method("POST", postData)
            } else {
                // Explicitly set empty body. Otherwise okhttp sends GET
                RequestBody emptyBody = RequestBody.create("", null)
                request.method("POST", emptyBody)
            }

            request.build()
        }
    }

    private String getCrumb() {
        log.trace("Getting Crumb for Jenkins")
        def response = sendRequestWithRetries { buildRequest("crumbIssuer/api/json").build() }

        if (response.code() != 200) {
            throw new RuntimeException("Could not create crumb. Status code ${response.code()}")
        }

        def json = new JsonSlurper().parse(response.body().byteStream())

        if (!json instanceof Map || !(json as Map).containsKey('crumb')) {
            throw new RuntimeException("Could not create crumb. Invalid json.")
        }

        return json['crumb']
    }

    private Request.Builder buildRequest(String url) {
        return new Request.Builder()
                .url("${config.jenkins.url}/$url")
                .header("Authorization", Credentials.basic(config.jenkins.username, config.jenkins.password))
    }

    // We pass a closure, so that we actually refetch a new crumb for a failed request
    // The Jenkins ApiClient has it's own retry logic on top of RetryInterceptor, because of crumb lifetime and restarts
    private Response sendRequestWithRetries(Closure<Request> request) {
        def retry = 0
        Response response = null
        do {
            response = client.newCall(request()).execute()
            if (!shouldRetryRequest(response)) {
                break
            }
            Thread.sleep(waitPeriodInMs)
        } while (++retry < maxRetries)

        return response
    }

    private boolean shouldRetryRequest(Response response) {
        // We might run into a 403 due to an invalid crumb from a previous session before jenkins was restarted.
        // Here in the ApiClient, we simply retry all 401 and 403 including fetching a new crumb
        return response.code() in [401, 403]
    }

    protected  void setMaxRetries(int retries) {
        this.maxRetries = retries
    }
    
    protected setWaitPeriodInMs(int waitPeriodInMs) {
        this.waitPeriodInMs = waitPeriodInMs
    }

}
