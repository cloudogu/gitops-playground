package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.config.Config;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micronaut.context.ApplicationContext;
import okhttp3.FormBody;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JenkinsApiClientTest {

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance()
		.options(wireMockConfig()
			.dynamicPort()
			.dynamicHttpsPort())
		.build();

	@Test
	void runsScriptWithCrumb() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}")));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("ok")));

		OkHttpClient httpClient = getUnsafeOkHttpClient().newBuilder()
			.cookieJar(new JavaNetCookieJar(new CookieManager()))
			.build();
		Config config = new Config();
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl() + "/jenkins");
		config.setJenkins(jenkins);
		JenkinsApiClient apiClient = new JenkinsApiClient(config, httpClient);

		String result = apiClient.runScript("println('ok')");
		assertThat(result).isEqualTo("ok");

		wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.withHeader("Authorization", matching("Basic .*")));

		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/scriptText"))
			.withHeader("Authorization", matching("Basic .*"))
			.withHeader("Jenkins-Crumb", equalTo("the-crumb")));
	}

	@Test
	void addsCrumbToSendRequest() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}")));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/foobar"))
			.willReturn(aResponse().withStatus(200)));

		Config config = new Config();
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl() + "/jenkins");
		config.setJenkins(jenkins);
		JenkinsApiClient client = new JenkinsApiClient(config, getUnsafeOkHttpClient());
		client.postRequestWithCrumb("foobar");

		wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")));
		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/foobar"))
			.withHeader("Jenkins-Crumb", equalTo("the-crumb")));
	}

	@Test
	void addsCrumbAndPostDataToSendRequest() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}")));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/foobar"))
			.willReturn(aResponse().withStatus(200)));

		Config config = new Config();
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl() + "/jenkins");
		config.setJenkins(jenkins);
		JenkinsApiClient client = new JenkinsApiClient(config, getUnsafeOkHttpClient());
		client.postRequestWithCrumb("foobar", new FormBody.Builder().add("key", "value with spaces").build());

		wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")));
		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/foobar"))
			.withHeader("Jenkins-Crumb", equalTo("the-crumb"))
			.withFormParam("key", equalTo("value with spaces")));
	}

	@Test
	void allowsSelfSignedCertificatesWhenUsingInsecure() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}")));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("ok")));

		Config config = new Config();
		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setInsecure(true);
		config.setApplication(application);
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl().replace("http://", "https://") + "/jenkins");
		config.setJenkins(jenkins);

		JenkinsApiClient apiClient = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(JenkinsApiClient.class);

		String result = apiClient.runScript("println('ok')");
		assertThat(result).isEqualTo("ok");

		wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.withHeader("Authorization", matching("Basic .*")));

		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/scriptText"))
			.withHeader("Authorization", matching("Basic .*"))
			.withHeader("Jenkins-Crumb", equalTo("the-crumb")));
	}

	@Test
	void retriesOnInvalidCrumb() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.inScenario("Invalid Crumb Retry")
			.whenScenarioStateIs("Started")
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-invalid-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}"))
			.willSetStateTo("First Crumb"));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
			.inScenario("Invalid Crumb Retry")
			.whenScenarioStateIs("First Crumb")
			.withHeader("Jenkins-Crumb", equalTo("the-invalid-crumb"))
			.willReturn(aResponse()
				.withStatus(403)
				.withBody("{\"servlet\":\"Stapler\", \"message\":\"No valid crumb was included in the request\", \"url\":\"/scriptText\", \"status\":\"403\"}"))
			.willSetStateTo("Invalid Crumb Response"));

		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.inScenario("Invalid Crumb Retry")
			.whenScenarioStateIs("Invalid Crumb Response")
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-second-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}"))
			.willSetStateTo("Second Crumb"));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
			.inScenario("Invalid Crumb Retry")
			.whenScenarioStateIs("Second Crumb")
			.withHeader("Jenkins-Crumb", equalTo("the-second-crumb"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("ok")));

		OkHttpClient httpClient = getUnsafeOkHttpClient();
		Config config = new Config();
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl() + "/jenkins");
		config.setJenkins(jenkins);
		JenkinsApiClient apiClient = new JenkinsApiClient(config, httpClient);
		apiClient.setMaxRetries(3);
		apiClient.setWaitPeriodInMs(0);

		String result = apiClient.runScript("println('ok')");
		assertThat(result).isEqualTo("ok");

		wireMock.verify(2, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")));
		wireMock.verify(2, postRequestedFor(urlPathEqualTo("/jenkins/scriptText")));
	}

	@Test
	void retriesOnInvalidCrumbAreLimited() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-invalid-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}")));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
			.willReturn(aResponse()
				.withStatus(403)
				.withBody("{\"servlet\":\"Stapler\", \"message\":\"No valid crumb was included in the request\", \"url\":\"/scriptText\", \"status\":\"403\"}")));

		OkHttpClient httpClient = getUnsafeOkHttpClient();
		Config config = new Config();
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl() + "/jenkins");
		config.setJenkins(jenkins);
		JenkinsApiClient apiClient = new JenkinsApiClient(config, httpClient);
		apiClient.setMaxRetries(3);
		apiClient.setWaitPeriodInMs(0);

		assertThrows(RuntimeException.class, () -> apiClient.runScript("println('ok')"));

		wireMock.verify(3, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")));
		wireMock.verify(3, postRequestedFor(urlPathEqualTo("/jenkins/scriptText")));
	}

	@Test
	void retriesWhenFetchingCrumbFails() {
		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.inScenario("Crumb Fetch Retry")
			.whenScenarioStateIs("Started")
			.willReturn(aResponse()
				.withStatus(401)
				.withBody("error"))
			.willSetStateTo("First Attempt Failed"));

		wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
			.inScenario("Crumb Fetch Retry")
			.whenScenarioStateIs("First Attempt Failed")
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("{\"crumb\": \"the-invalid-crumb\", \"crumbRequestField\": \"Jenkins-Crumb\"}")));

		wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("ok")));

		OkHttpClient httpClient = getUnsafeOkHttpClient();
		Config config = new Config();
		Config.JenkinsSchema jenkins = new Config.JenkinsSchema();
		jenkins.setUrl(wireMock.baseUrl() + "/jenkins");
		config.setJenkins(jenkins);
		JenkinsApiClient apiClient = new JenkinsApiClient(config, httpClient);
		apiClient.setMaxRetries(3);
		apiClient.setWaitPeriodInMs(0);

		String result = apiClient.runScript("println('ok')");
		assertThat(result).isEqualTo("ok");

		wireMock.verify(2, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")));
		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/scriptText")));
	}

	private static OkHttpClient getUnsafeOkHttpClient() {
		try {
			TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType) {
				}

				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType) {
				}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			}};

			SSLContext sslContext = SSLContext.getInstance("SSL");
			sslContext.init(null, trustAllCerts, new SecureRandom());
			SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

			return new OkHttpClient.Builder()
				.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
				.hostnameVerifier((hostname, session) -> true)
				.build();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
