package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.utils.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Singleton
public class JobManager {

    private static final Logger log = LoggerFactory.getLogger(JobManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final JenkinsApiClient apiClient;

    public JobManager(JenkinsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void createCredential(String jobName, String id, String username, String password, String description) {
        try {
            Map<String, Object> innerMap = new LinkedHashMap<>();
            innerMap.put("scope", "GLOBAL");
            innerMap.put("id", id);
            innerMap.put("username", username);
            innerMap.put("password", password);
            innerMap.put("description", description);
            innerMap.put("$class", "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl");

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("credentials", innerMap);

            String jsonPayload = objectMapper.writeValueAsString(payloadMap);

            try (var response = apiClient.postRequestWithCrumb("job/" + jobName + "/credentials/store/folder/domain/_/createCredentials",
                    new FormBody.Builder().add("json", jsonPayload).build())) {
                if (response.code() != 200) {
                    throw new RuntimeException("Could not create credential id=" + id + ",job=" + jobName + ". StatusCode: " + response.code());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize or send credential request", e);
        }
    }

    /**
     * @return true, if created; false if job already exists and nothing was changed.
     */
    public boolean createJob(String name, String serverUrl, String jobNamespace, String credentialsId) {
        if (jobExists(name)) {
            log.warn("Job '{}' already exists, ignoring.", name);
            return false;
        } else {
            try {
                // Note for development: the XML representation of an existing job can be exporting by adding /config.xml to the URL
                String payloadXml = new TemplatingEngine().template(new File("argocd/cluster-resources/apps/jenkins/templates/namespaceJobTemplate.xml.ftl"),
                        Map.of("SCMM_NAMESPACE_JOB_SERVER_URL", serverUrl,
                               "SCMM_NAMESPACE_JOB_NAMESPACE", jobNamespace,
                               "SCMM_NAMESPACE_JOB_CREDENTIALS_ID", credentialsId));

                RequestBody body = RequestBody.create(payloadXml, MediaType.get("text/xml"));

                try (var response = apiClient.postRequestWithCrumb("createItem?name=" + name, body)) {
                    if (response.code() != 200) {
                        throw new RuntimeException("Could not create job '" + name + "'. StatusCode: " + response.code());
                    }
                }
            } catch (IOException | freemarker.template.TemplateException e) {
                throw new RuntimeException("Failed to prepare or deploy Helm chart / template XML", e);
            }
        }
        return true;
    }

    public boolean jobExists(String name) {
        try (var response = apiClient.postRequestWithCrumb("job/" + name)) {
            return response.code() == 200;
        }
    }

    public void deleteJob(String name) {
        if (name.contains("'")) {
            throw new RuntimeException("Job name cannot contain quotes.");
        }

        String script = "print(Jenkins.instance.getItem('" + name + "')?.delete())";
        String result = apiClient.runScript(script);

        if (!"null".equals(result)) {
            throw new RuntimeException("Could not delete job " + name);
        }
    }

    public void startJob(String jobName) {
        try (var response = apiClient.postRequestWithCrumb("job/" + jobName + "/build?delay=0sec")) {
            if (response.code() != 200) {
                throw new RuntimeException("Could not trigger build of Jenkins job: " + jobName + ". StatusCode: " + response.code());
            }
        }
    }
}
