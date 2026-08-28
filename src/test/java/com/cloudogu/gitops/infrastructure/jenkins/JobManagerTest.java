package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.config.Config;
import com.github.tomakehurst.wiremock.WireMockServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobManagerTest {

	@Test
	void createsCredential() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathMatching(".*createCredentials.*"))
				.willReturn(ok()));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			jobManager.createCredential("the-jobname", "the-id", "the-username", "the-password", "some description");

			wireMockServer.verify(postRequestedFor(urlPathEqualTo(
				"/jenkins/job/the-jobname/credentials/store/folder/domain/_/createCredentials")));

			var requests = wireMockServer.findAll(postRequestedFor(urlPathMatching(".*createCredentials.*")));
			assertThat(requests).hasSize(1);

			String requestBody = requests.get(0).getBodyAsString();
			assertThat(URLDecoder.decode(requestBody, StandardCharsets.UTF_8))
				.isEqualTo(
					"json={\"credentials\":{\"scope\":\"GLOBAL\",\"id\":\"the-id\",\"username\":\"the-username\",\"password\":\"the-password\",\"description\":\"some description\",\"$class\":\"com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl\"}}");

		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void throwsWhenCreatingCredentialFails() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathMatching(".*createCredentials.*"))
				.willReturn(aResponse().withStatus(404)));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> jobManager.createCredential(
					"the-jobname",
					"the-id",
					"the-username",
					"the-password",
					"some description"
				)
			);
			assertThat(exception.getMessage()).isEqualTo(
				"Could not create credential id=the-id,job=the-jobname. StatusCode: 404");
		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void startsJob() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathMatching("/jenkins/job/the-jobname/build.*"))
				.willReturn(ok()));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			jobManager.startJob("the-jobname");

			wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname/build"))
				.withQueryParam("delay", equalTo("0sec")));

		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void throwsWhenStartingJobFails() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathMatching("/jenkins/job/the-jobname/build.*"))
				.willReturn(aResponse().withStatus(400)));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> jobManager.startJob("the-jobname")
			);
			assertThat(exception.getMessage()).isEqualTo(
				"Could not trigger build of Jenkins job: the-jobname. StatusCode: 400");
		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void throwsWhenJobContainsInvalidCharacters() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		JobManager jobManager = new JobManager(client);

		RuntimeException exception = assertThrows(RuntimeException.class, () -> jobManager.deleteJob("foo'foo"));
		assertThat(exception.getMessage()).isEqualTo("Job name cannot contain quotes.");
	}

	@Test
	void throwsWhenJobDeletionFails() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		JobManager jobManager = new JobManager(client);

		RuntimeException exception = assertThrows(RuntimeException.class, () -> jobManager.deleteJob("foo-foo"));
		assertThat(exception.getMessage()).isEqualTo("Could not delete job foo-foo");
	}

	@Test
	void deletesJob() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		JobManager jobManager = new JobManager(client);

		when(client.runScript(anyString())).thenReturn("null");
		jobManager.deleteJob("foo");
		verify(client).runScript("print(Jenkins.instance.getItem('foo')?.delete())");
	}

	@Test
	void checksExistingJob() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
				.willReturn(ok()));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			boolean exists = jobManager.jobExists("the-jobname");

			assertThat(exists).isEqualTo(true);
			wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")));
		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void checksNonExistingJob() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
				.willReturn(aResponse().withStatus(404)));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			boolean exists = jobManager.jobExists("the-jobname");
			assertThat(exists).isEqualTo(false);
			wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")));
		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void createsJob() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));
			wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
				.willReturn(aResponse().withStatus(404)));
			wireMockServer.stubFor(post(urlPathMatching("/jenkins/createItem.*"))
				.willReturn(ok()));

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			boolean created = jobManager.createJob("the-jobname", "http://scm", "ns", "creds");

			assertThat(created).isEqualTo(true);

			wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")));
			wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/createItem"))
				.withQueryParam("name", equalTo("the-jobname"))
				.withRequestBody(containing("<serverUrl>http://scm</serverUrl>"))
				.withRequestBody(containing("<namespace>ns</namespace>"))
				.withRequestBody(containing("<credentialsId>creds</credentialsId>")));

		} finally {
			wireMockServer.stop();
		}
	}

	@Test
	void ignoresExistingJob() {
		WireMockServer wireMockServer = new WireMockServer(options().dynamicPort());
		wireMockServer.start();

		try {
			wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
				.willReturn(okJson("{\"crumb\":\"the-crumb\"}")));

			wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
				.willReturn(ok())); // 200 OK means "Job Exists"

			Config config = new Config();
			Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
			jenkins.setUrl(wireMockServer.baseUrl() + "/jenkins");
			config.setJenkins(jenkins);
			JobManager jobManager = new JobManager(new JenkinsApiClient(config, new OkHttpClient()));

			boolean created = jobManager.createJob("the-jobname", "http://scm", "ns", "creds");

			assertThat(created).isEqualTo(false);
			wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")));
			wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/jenkins/createItem")));

		} finally {
			wireMockServer.stop();
		}
	}
}
