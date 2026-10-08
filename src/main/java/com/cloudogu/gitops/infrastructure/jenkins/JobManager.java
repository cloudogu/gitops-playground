package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.utils.TemplatingEngine;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class JobManager {

	private static final int HTTP_OK = 200;
	private static final int HTTP_CONFLICT = 409;


	private final JenkinsApiClient apiClient;

	public void createOrUpdateCredential(String jobName, String id, String username, String password, String description) {
		RequestBody body = RequestBody.create(
			credentialXml(id, username, password, description),
			MediaType.get("text/xml")
		);

		try (Response response = apiClient.postRequestWithCrumb(
			"job/" + jobName + "/credentials/store/folder/domain/_/createCredentials",
			body
		)) {
			if (response.code() == HTTP_CONFLICT) {
				updateCredential(jobName, id, username, password, description);
				return;
			}
			if (response.code() != HTTP_OK) {
				throw new IllegalStateException("Could not create credential id=" + id + ",job=" + jobName + ". StatusCode: " + response.code());
			}
		}
	}

	private void updateCredential(String jobName, String id, String username, String password, String description) {
		RequestBody body = RequestBody.create(
			credentialXml(id, username, password, description),
			MediaType.get("text/xml")
		);

		try (Response response = apiClient.postRequestWithCrumb(
			"job/" + jobName + "/credentials/store/folder/domain/_/credential/" + id + "/config.xml",
			body
		)) {
			if (response.code() != HTTP_OK) {
				throw new IllegalStateException(
					"Could not update credential id=" + id + ",job=" + jobName + ". StatusCode: " + response.code()
				);
			}
		}
	}

	private String credentialXml(String id, String username, String password, String description) {
		try {
			StringWriter writer = new StringWriter();
			XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);

			xml.writeStartElement("com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl");
			writeXmlElement(xml, "scope", "GLOBAL");
			writeXmlElement(xml, "id", id);
			writeXmlElement(xml, "description", description);
			writeXmlElement(xml, "username", username);
			writeXmlElement(xml, "password", password);
			xml.writeEndElement();
			xml.close();

			return writer.toString();
		} catch (XMLStreamException e) {
			throw new IllegalStateException("Could not serialize Jenkins credential id=" + id, e);
		}
	}

	private void writeXmlElement(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
		xml.writeStartElement(name);
		xml.writeCharacters(value);
		xml.writeEndElement();
	}

	/**
	 * @return true, if created; false if job already exists and nothing was changed.
	 */
	public boolean createJob(String name, String serverUrl, String jobNamespace, String credentialsId) {
		if (jobExists(name)) {
			log.warn("Job '{}' already exists, ignoring.", name);
			return false;
		}
		createJobViaApi(name, serverUrl, jobNamespace, credentialsId);
		return true;
	}

	private void createJobViaApi(String name, String serverUrl, String jobNamespace, String credentialsId) {
		try {
			// Note for development: the XML representation of an existing job can be exporting by
			// adding /config.xml to the URL
			String payloadXml = new TemplatingEngine().template(
				new File("argocd/cluster-resources/apps/jenkins/templates/namespaceJobTemplate.xml.ftl"),
				Map.of(
					"SCMM_NAMESPACE_JOB_SERVER_URL",
					serverUrl,
					"SCMM_NAMESPACE_JOB_NAMESPACE",
					jobNamespace,
					"SCMM_NAMESPACE_JOB_CREDENTIALS_ID",
					credentialsId
				)
			);

			RequestBody body = RequestBody.create(payloadXml, MediaType.get("text/xml"));

			try (Response response = apiClient.postRequestWithCrumb("createItem?name=" + name, body)) {
				if (response.code() != HTTP_OK) {
					throw new IllegalStateException("Could not create job '" + name + "'. StatusCode: " + response.code());
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
		try (Response response = apiClient.postRequestWithCrumb("job/" + jobName + "/build?delay=0sec")) {
			if (response.code() != HTTP_OK) {
				throw new IllegalStateException("Could not trigger build of Jenkins job: " + jobName + ". StatusCode: " + response.code());
			}
		}
	}
}
