package com.cloudogu.gitops.jenkins

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Slf4j
class ApiClient {
    private String jenkinsUrl
    private String username
    private String password

    private OkHttpClient client

    ApiClient(
            String jenkinsUrl,
            String username,
            String password,
            OkHttpClient client
    ) {
        this.client = client
        this.jenkinsUrl = jenkinsUrl
        this.password = password
        this.username = username
    }

    String runScript(String code) {
        def crumb = getCrumb()

        log.trace("Running groovy script in Jenkins: {}", code)
        def request = buildRequest("scriptText")
            .header("Jenkins-Crumb", crumb)
            .post(new FormBody.Builder().add("script", code).build())
            .build()

        def response = client.newCall(request).execute()
        if (response.code() != 200) {
            throw new RuntimeException("Could not run script. Status code ${response.code()}")
        }

        return response.body().string()
    }

    Response sendRequest(String url, FormBody postData) {
        Request.Builder request = buildRequest(url)
            .header("Jenkins-Crumb", getCrumb())

        if (postData != null) {
            request.method("POST", postData)
        }

        return client.newCall(request.build()).execute()
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
}
