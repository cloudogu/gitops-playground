package com.cloudogu.gitops.jenkins

import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import jakarta.inject.Singleton
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.RequestBody
import org.intellij.lang.annotations.Language

@Singleton
@Slf4j
class JobManager {
    private JenkinsApiClient apiClient

    JobManager(JenkinsApiClient apiClient) {
        this.apiClient = apiClient
    }

    void createCredential(String jobName, String id, String username, String password, String description) {
        def response = apiClient.postRequestWithCrumb(
                "job/$jobName/credentials/store/folder/domain/_/createCredentials",
                new FormBody.Builder()
                        .add("json", JsonOutput.toJson([
                                credentials: [
                                        scope      : "GLOBAL",
                                        id         : id,
                                        username   : username,
                                        password   : password,
                                        description: description,
                                        $class    : "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl",
                                ]
                        ]))
                        .build()
        )

        if (response.code() != 200) {
            throw new RuntimeException("Could not create credential id=$id,job=$jobName. StatusCode: ${response.code()}")
        }
    }

    /**
     * @return true, if created; false if job already exists and nothing was changed.
     */
    boolean createJob(String name, String serverUrl, String jobNamespace, String credentialsId) {
        if (jobExists(name)) {
            log.warn("Job '${name}' already exists, ignoring.")
            return false
        } else {
            // Note for development: the XML representation of an existing job can be exporting by adding /config.xml to the URL
            String payloadXml = new TemplatingEngine().template(new File('jenkins/namespaceJobTemplate.xml.ftl'),
                    [
                            SCMM_NAMESPACE_JOB_SERVER_URL    : serverUrl,
                            SCMM_NAMESPACE_JOB_NAMESPACE     : jobNamespace,
                            SCMM_NAMESPACE_JOB_CREDENTIALS_ID: credentialsId
                    ])

            RequestBody body = RequestBody.create(payloadXml, MediaType.get("text/xml"))

            def response = apiClient.postRequestWithCrumb("createItem?name=$name", body)

            if (response.code() != 200) {
                throw new RuntimeException("Could not create job '${name}'. StatusCode: ${response.code()}")
            }
        }
        return true
    }
    
    boolean jobExists(String name) {
        def response= apiClient.postRequestWithCrumb("job/$name")

        return response.code() == 200
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

    void startJob(String jobName) {

        def response= apiClient.postRequestWithCrumb(
                "job/$jobName/build?delay=0sec")

        if (response.code() != 200) {
            throw new RuntimeException("Could not trigger build of Jenkins job: $jobName. StatusCode: ${response.code()}")
        }
    }
}
