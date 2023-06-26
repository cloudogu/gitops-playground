package com.cloudogu.gitops.jenkins

import groovy.json.JsonSlurper
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

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

    private String getCrumb() {
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
