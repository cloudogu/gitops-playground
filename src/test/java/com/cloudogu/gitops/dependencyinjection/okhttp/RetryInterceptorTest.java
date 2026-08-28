package com.cloudogu.gitops.dependencyinjection.okhttp;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryInterceptorTest {

	private static final int OKHTTPCLIENT_TIMEOUT = 1000;

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
															   .options(wireMockConfig()
																   .dynamicPort()
																   .dynamicHttpsPort())
															   .build();

	@BeforeEach
	void resetWireMock() {
		wireMock.resetAll();
	}

	@Test
	void retriesThreeTimesOn500() throws IOException, GeneralSecurityException {
		String path = "/retry-500";

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("Retry Scenario")
			.whenScenarioStateIs("Started")
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("First Retry"));

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("Retry Scenario")
			.whenScenarioStateIs("First Retry")
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("Second Retry"));

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("Retry Scenario")
			.whenScenarioStateIs("Second Retry")
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("Successful Result")));

		OkHttpClient client = createClient();
		Response response = client.newCall(new Request.Builder().url(wireMock.baseUrl() + path).build()).execute();
		assertThat(response.body().string()).isEqualTo("Successful Result");
		wireMock.verify(3, getRequestedFor(urlEqualTo(path)));
	}

	@Test
	void retriesThreeTimesOn500WithHttps() throws IOException, GeneralSecurityException {
		String path = "/retry-500";

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("HTTPS Retry Scenario")
			.whenScenarioStateIs("Started")
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("First Retry"));

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("HTTPS Retry Scenario")
			.whenScenarioStateIs("First Retry")
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("Second Retry"));

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("HTTPS Retry Scenario")
			.whenScenarioStateIs("Second Retry")
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("Successful Result")));

		OkHttpClient client = createClient();
		Response response = client.newCall(new Request.Builder().url(wireMock.baseUrl() + path).build()).execute();
		assertThat(response.body().string()).isEqualTo("Successful Result");
		wireMock.verify(3, getRequestedFor(urlEqualTo(path)));
	}

	@Test
	void retriesOnTimeout() throws IOException, GeneralSecurityException {
		String path = "/timeout-test";

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("Timeout Scenario")
			.whenScenarioStateIs("Started")
			.willReturn(aResponse()
				.withStatus(200)
				.withFixedDelay(2000))
			.willSetStateTo("After Timeout"));

		wireMock.stubFor(get(urlEqualTo(path))
			.inScenario("Timeout Scenario")
			.whenScenarioStateIs("After Timeout")
			.willReturn(aResponse()
				.withStatus(200)
				.withBody("Successful Result")));

		OkHttpClient client = createClient(100);
		Response response = client.newCall(new Request.Builder().url(wireMock.baseUrl() + path).build()).execute();
		assertThat(response.body().string()).isEqualTo("Successful Result");
		wireMock.verify(2, getRequestedFor(urlEqualTo(path)));
	}

	@Test
	void failsAfterThirdRetry() throws GeneralSecurityException {
		String path = "/always-fail";

		wireMock.stubFor(get(urlEqualTo(path))
			.willReturn(aResponse().withStatus(500)));

		OkHttpClient client = createClient();

		IOException exception = assertThrows(
			IOException.class, () ->
				client.newCall(new Request.Builder().url(wireMock.baseUrl() + path).build()).execute()
		);

		assertThat(exception.getMessage()).contains("500");
		wireMock.verify(4, getRequestedFor(urlEqualTo(path)));
	}

	private OkHttpClient createClient() throws GeneralSecurityException {
		return createClient(OKHTTPCLIENT_TIMEOUT);
	}

	private OkHttpClient createClient(int timeout) throws GeneralSecurityException {
		X509TrustManager trustManager = new X509TrustManager() {
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
		};
		TrustManager[] trustAllCerts = new TrustManager[]{trustManager};

		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(null, trustAllCerts, new SecureRandom());

		return new OkHttpClient.Builder()
			.addInterceptor(new RetryInterceptor(3, 0))
			.connectTimeout(timeout, TimeUnit.MILLISECONDS)
			.readTimeout(timeout, TimeUnit.MILLISECONDS)
			.sslSocketFactory(sslContext.getSocketFactory(), trustManager)
			.hostnameVerifier((hostname, session) -> true)
			.build();
	}
}
