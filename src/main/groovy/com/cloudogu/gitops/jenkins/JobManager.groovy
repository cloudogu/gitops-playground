package com.cloudogu.gitops.jenkins

import jakarta.inject.Singleton
import org.intellij.lang.annotations.Language

@Singleton
class JobManager {
    private ApiClient apiClient

    JobManager(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void deleteJob(String name) {
        if (name.contains("'")) {
            throw new RuntimeException('Job name cannot contain quotes.')
        }

        @Language("groovy")
        String script = "print(Jenkins.instance.getItem('$name').delete())"
        def result = apiClient.runScript(script)

        if (result != 'null') {
            throw new RuntimeException("Could not delete job $name")
        }
    }
}
