package com.cloudogu.gitops.jenkins

import groovy.json.JsonOutput
import jakarta.inject.Singleton
import okhttp3.FormBody
import org.intellij.lang.annotations.Language

@Singleton
class JobManager {
    private ApiClient apiClient

    JobManager(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void createCredential(String jobName, String id, String username, String password, String description) {
        def response = apiClient.sendRequestWithCrumb(
                "job/$jobName/credentials/store/folder/domain/_/createCredentials",
                new FormBody.Builder()
                        .add("json", JsonOutput.toJson([
                                credentials: [
                                        "scope"      : "GLOBAL",
                                        "id"         : id,
                                        "username"   : username,
                                        "password"   : password,
                                        "description": description,
                                        "\$class"    : "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl",
                                ]
                        ]))
                        .build()
        )

        if (response.code() != 200) {
            throw new RuntimeException("Could not create credential. StatusCode: ${response.code()}")
        }
    }

    void deleteJob(String name) {
        if (name.contains("'")) {
            throw new RuntimeException('Job name cannot contain quotes.')
        }

        @Language("groovy")
        String script = "print(Jenkins.instance.getItem('$name')?.delete())"
        def result = apiClient.runScript(script)

        if (result != 'null') {
            throw new RuntimeException("Could not delete job $name")
        }
    }
}
