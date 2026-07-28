package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.utils.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class JobManager {

private static final int HTTP_OK = 200;

private static final ObjectMapper objectMapper = new ObjectMapper();

private final JenkinsApiClient apiClient;

public void createCredential(
	String jobName, String id, String username, String password, String description) {
	try {
	Map<String, Object> innerMap = new LinkedHashMap<>();
	innerMap.put("scope", "GLOBAL");
	innerMap.put("id", id);
	innerMap.put("username", username);
	innerMap.put("password", password);
	innerMap.put("description", description);
	innerMap.put(
		"$class", "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl");

	Map<String, Object> payloadMap = new LinkedHashMap<>();
	payloadMap.put("credentials", innerMap);

	String jsonPayload = objectMapper.writeValueAsString(payloadMap);

	try (Response response =
		apiClient.postRequestWithCrumb(
			"job/" + jobName + "/credentials/store/folder/domain/_/createCredentials",
			new FormBody.Builder().add("json", jsonPayload).build())) {
		if (response.code() != HTTP_OK) {
		throw new IllegalStateException(
			"Could not create credential id="
				+ id
				+ ",job="
				+ jobName
				+ ". StatusCode: "
				+ response.code());
		}
	}
	} catch (IOException e) {
	throw new UncheckedIOException("Failed to serialize or send credential request", e);
	}
}

/**
* @return true, if created; false if job already exists and nothing was changed.
*/
public boolean createJob(
	String name, String serverUrl, String jobNamespace, String credentialsId) {
	if (jobExists(name)) {
	log.warn("Job '{}' already exists, ignoring.", name);
	return false;
	}
	createJobViaApi(name, serverUrl, jobNamespace, credentialsId);
	return true;
}

private void createJobViaApi(
	String name, String serverUrl, String jobNamespace, String credentialsId) {
	try {
	// Note for development: the XML representation of an existing job can be exporting by
	// adding /config.xml to the URL
	String payloadXml =
		new TemplatingEngine()
			.template(
				new File(
					"argocd/cluster-resources/apps/jenkins/templates/namespaceJobTemplate.xml.ftl"),
				Map.of(
					"SCMM_NAMESPACE_JOB_SERVER_URL", serverUrl,
					"SCMM_NAMESPACE_JOB_NAMESPACE", jobNamespace,
					"SCMM_NAMESPACE_JOB_CREDENTIALS_ID", credentialsId));

	RequestBody body = RequestBody.create(payloadXml, MediaType.get("text/xml"));

	try (Response response = apiClient.postRequestWithCrumb("createItem?name=" + name, body)) {
		if (response.code() != HTTP_OK) {
		throw new IllegalStateException(
			"Could not create job '" + name + "'. StatusCode: " + response.code());
		}
	}
	} catch (IOException | freemarker.template.TemplateException e) {
	throw new RuntimeException("Failed to prepare or deploy Helm chart / template XML", e);
	}
}

public boolean jobExists(String name) {
	try (Response response = apiClient.postRequestWithCrumb("job/" + name)) {
	return response.code() == HTTP_OK;
	}
}

public void deleteJob(String name) {
	if (name.contains("'")) {
	throw new IllegalArgumentException("Job name cannot contain quotes.");
	}

	String script = "print(Jenkins.instance.getItem('" + name + "')?.delete())";
	String result = apiClient.runScript(script);

	if (!"null".equals(result)) {
	throw new IllegalStateException("Could not delete job " + name);
	}
}

public void startJob(String jobName) {
	try (Response response =
		apiClient.postRequestWithCrumb("job/" + jobName + "/build?delay=0sec")) {
	if (response.code() != HTTP_OK) {
		throw new IllegalStateException(
			"Could not trigger build of Jenkins job: "
				+ jobName
				+ ". StatusCode: "
				+ response.code());
	}
	}
}
}
