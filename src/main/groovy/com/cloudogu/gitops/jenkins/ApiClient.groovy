package com.cloudogu.gitops.jenkins

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import okhttp3.*

@Slf4j
class ApiClient {
    private String jenkinsUrl
    private String username
    private String password

    private OkHttpClient client

    private int maxRetries
    private int waitPeriodInMs

    // Number of retries in uncommonly high, because we might have to  outlive a unexpected Jenkins restart
    ApiClient(
            String jenkinsUrl,
            String username,
            String password,
            OkHttpClient client,
            int retries = 180,
            int waitPeriodInMs = 2000
    ) {
        this.client = client
        this.jenkinsUrl = jenkinsUrl
        this.password = password
        this.username = username

        this.waitPeriodInMs = waitPeriodInMs
        this.maxRetries = retries
    }

    String runScript(String code) {
        log.trace("Running groovy script in Jenkins: {}", code)
        def response = sendRequest("scriptText", new FormBody.Builder().add("script", code).build())
        if (response.code() != 200) {
            throw new RuntimeException("Could not run script. Status code ${response.code()}")
        }

        return response.body().string()
    }

    Response sendRequest(String url, FormBody postData) {
        def retry = 0
        Response response = null
        do {
            Request.Builder request = buildRequest(url)
                    .header("Jenkins-Crumb", getCrumb())

            if (postData != null) {
                request.method("POST", postData)
            }

            response = client.newCall(request.build()).execute()
            if (!shouldRetryRequest(response)) {
                break
            }
            Thread.sleep(waitPeriodInMs)
        } while(++retry < maxRetries)

        return response
    }

    private String getCrumb() {
        log.trace("Getting Crumb for Jenkins")
        def request  = buildRequest("crumbIssuer/api/json").build()
        def response = client.newCall(request).execute()

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
                .url("$jenkinsUrl/$url")
                .header("Authorization", Credentials.basic(username, password))
    }

    private boolean shouldRetryRequest(Response response) {
        // We might run into a 403 due to an invalid crumb from a previous session before jenkins was restarted.
        // Here in the ApiClient, we simply retry all 401 and 403 including fetching a new crumb
        return response.code() in [401, 403]
    }
}
